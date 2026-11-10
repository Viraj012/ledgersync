# LedgerSync — Project Spec

## What this is
LedgerSync is a small backend service that keeps **payment records** (from Stripe) and **accounting records** (in an ERP) in sync, and flags when they drift apart.

The problem it solves: a business takes payments in Stripe, but its finance/ERP system (NetSuite, QuickBooks, etc.) is updated separately. Webhooks get retried, ERP calls fail, refunds get missed, and finance teams end up reconciling by hand. LedgerSync automates that.

**Flow:** Stripe webhook → verify + deduplicate → store order/payment → queue a sync job (same transaction) → background worker pushes to ERP with retries → scheduled job reconciles Stripe-side vs ERP-side records and reports mismatches.

## Goals (keep it small)
1. Receive Stripe payment and refund webhooks **without ever creating duplicates**.
2. Store orders/payments in Postgres and **sync them to an ERP API with retries**.
3. Run a **reconciliation job** that finds and reports mismatches.
4. Be clean, tested, documented, and runnable with one `docker compose up`.

## Non-goals (do NOT build these)
- No Kafka, no message brokers, no microservices split. One Spring Boot app + Postgres.
- No frontend/UI.
- No real ERP integration. The ERP is a mock inside this app.
- No auth system for users. (Webhook signature verification is the only security boundary that matters here.)
- Stretch goals are listed at the bottom. Do not start them until core is done.

## Ground rules for working on this repo
- **Viraj writes or line-by-line reviews** the interview-critical logic: webhook idempotency, the sync worker's locking + retry logic, and the reconciliation logic. Scaffolding, configs, boilerplate, and tests can be generated, but explain these three areas clearly when touching them.
- Small, focused commits with clear messages. Commit at least once per working day.
- Never commit secrets. Stripe keys and webhook secret come from environment variables / `.env` (gitignored). Provide `.env.example`.
- Prefer simple, readable code over clever abstractions.
- Every feature lands with tests.

## Tech stack
- **Java 21**, **Spring Boot 3.x** (Web, Data JPA or JdbcTemplate, Validation, Actuator, Scheduling)
- **Maven** (or Gradle, pick one and stay consistent)
- **PostgreSQL 16**, schema managed with **Flyway** migrations
- **Stripe Java SDK** (use `Webhook.constructEvent` for signature verification)
- **JUnit 5**, **Testcontainers** (real Postgres in integration tests), Mockito where useful
- **Docker** + **Docker Compose** (app + Postgres)
- **GitHub Actions** CI: build + run all tests on every push/PR

## Architecture

```
Stripe ──webhook──▶ WebhookController
                     │ 1. verify signature (reject 400 if invalid)
                     │ 2. insert into stripe_events (UNIQUE event_id) → duplicate? return 200, do nothing
                     │ 3. upsert order/payment or record refund
                     │ 4. insert sync_job (status=PENDING)
                     │    (steps 2–4 in ONE database transaction)
                     ▼
                 Postgres
                     ▲
SyncWorker (@Scheduled, every few seconds)
   - claims due jobs: SELECT ... FOR UPDATE SKIP LOCKED LIMIT n
   - calls ERP client
   - success → SUCCEEDED; failure → attempts++, next_attempt_at = backoff
   - attempts >= max → FAILED (this is the failure queue)
                     │
                     ▼
              Mock ERP (/mock-erp/**, stores erp_records, configurable failure rate)

ReconciliationJob (@Scheduled daily + manual trigger endpoint)
   - compares payments vs erp_records → writes reconciliation_runs + reconciliation_issues
```

### Packages (suggested)
```
com.ledgersync
  ├── webhook        // controller, Stripe event handling
  ├── order          // order/payment entities, repository, service
  ├── sync           // sync_jobs, worker, backoff, ERP client
  ├── erp            // mock ERP controller + repository (simulates an external system)
  ├── reconciliation // job, service, issue types, endpoints
  └── config
```

## Stripe events to handle
| Event | Action |
|---|---|
| `payment_intent.succeeded` | Create order + payment (status PAID), queue sync job `CREATE_INVOICE` |
| `charge.refunded` | Record refund against the payment, queue sync job `RECORD_REFUND` |
| anything else | Store in `stripe_events` as IGNORED, return 200 |

Always return **2xx fast** for valid signatures (Stripe retries on non-2xx). Return **400** for invalid signatures.

## Data model (Flyway V1)
```sql
CREATE TABLE stripe_events (
  id            BIGSERIAL PRIMARY KEY,
  event_id      TEXT NOT NULL UNIQUE,          -- Stripe's evt_... id: the idempotency key
  event_type    TEXT NOT NULL,
  status        TEXT NOT NULL,                 -- PROCESSED | IGNORED
  received_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orders (
  id                 BIGSERIAL PRIMARY KEY,
  payment_intent_id  TEXT NOT NULL UNIQUE,     -- Stripe pi_... id
  customer_email     TEXT,
  amount_cents       BIGINT NOT NULL,
  currency           TEXT NOT NULL,
  refunded_cents     BIGINT NOT NULL DEFAULT 0,
  status             TEXT NOT NULL,            -- PAID | PARTIALLY_REFUNDED | REFUNDED
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sync_jobs (
  id               BIGSERIAL PRIMARY KEY,
  order_id         BIGINT NOT NULL REFERENCES orders(id),
  job_type         TEXT NOT NULL,              -- CREATE_INVOICE | RECORD_REFUND
  status           TEXT NOT NULL,              -- PENDING | IN_PROGRESS | SUCCEEDED | FAILED
  attempts         INT NOT NULL DEFAULT 0,
  next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error       TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sync_jobs_due ON sync_jobs (status, next_attempt_at);

-- Mock ERP side (pretend this is another company's system)
CREATE TABLE erp_records (
  id                 BIGSERIAL PRIMARY KEY,
  external_ref       TEXT NOT NULL UNIQUE,     -- = payment_intent_id; makes ERP writes idempotent too
  amount_cents       BIGINT NOT NULL,
  refunded_cents     BIGINT NOT NULL DEFAULT 0,
  currency           TEXT NOT NULL,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reconciliation_runs (
  id             BIGSERIAL PRIMARY KEY,
  started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at    TIMESTAMPTZ,
  orders_checked INT NOT NULL DEFAULT 0,
  issues_found   INT NOT NULL DEFAULT 0
);

CREATE TABLE reconciliation_issues (
  id           BIGSERIAL PRIMARY KEY,
  run_id       BIGINT NOT NULL REFERENCES reconciliation_runs(id),
  order_id     BIGINT REFERENCES orders(id),
  external_ref TEXT,
  issue_type   TEXT NOT NULL,   -- MISSING_IN_ERP | AMOUNT_MISMATCH | REFUND_NOT_SYNCED | ORPHAN_IN_ERP
  details      TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## Key behaviors

### Idempotency (webhooks)
- Insert into `stripe_events` first. On unique-constraint violation on `event_id` → it's a duplicate → return 200 and do nothing.
- `orders.payment_intent_id` is also UNIQUE as a second safety net.
- Insert event + order change + sync job in **one transaction** so a crash can't leave an order without its sync job.

### Sync worker
- Runs on a fixed delay (e.g. every 5s, configurable).
- Claims jobs with `status = 'PENDING' AND next_attempt_at <= now()` using `FOR UPDATE SKIP LOCKED`, sets them `IN_PROGRESS`, commits, then calls the ERP.
- Backoff: `next_attempt_at = now() + base * 2^attempts`, capped (e.g. base 5s, cap 10 min). Add small jitter.
- `max_attempts` configurable (default 5). Exceeded → `FAILED`, keep `last_error`.
- Recovery: jobs stuck `IN_PROGRESS` longer than a timeout get reset to `PENDING`.
- ERP writes are idempotent via `external_ref` UNIQUE, so a retry after a timeout can't double-create.

### Mock ERP
- `POST /mock-erp/invoices` (create), `POST /mock-erp/invoices/{ref}/refunds` (record refund), `GET /mock-erp/invoices/{ref}`.
- Config: `erp.mock.failure-rate` (0.0–1.0) and `erp.mock.latency-ms` to simulate a flaky external system.
- The app's ERP client calls this over HTTP (RestClient/WebClient) with a timeout, exactly as if it were a real external API.

### Reconciliation
- Scheduled daily (cron configurable) + `POST /reconciliation/runs` to trigger manually.
- Checks:
  - `MISSING_IN_ERP`: order exists, no ERP record (and its sync job isn't still pending)
  - `AMOUNT_MISMATCH`: amounts or currency differ
  - `REFUND_NOT_SYNCED`: `orders.refunded_cents != erp_records.refunded_cents`
  - `ORPHAN_IN_ERP`: ERP record with no matching order
- Writes a run + its issues. `GET /reconciliation/runs/{id}` returns the report.

## API endpoints
| Method | Path | Purpose |
|---|---|---|
| POST | `/webhooks/stripe` | Stripe webhook receiver |
| GET | `/orders/{id}` | Order + sync status |
| GET | `/sync-jobs?status=FAILED` | Inspect the failure queue |
| POST | `/sync-jobs/{id}/retry` | Manually requeue a failed job |
| POST | `/reconciliation/runs` | Trigger reconciliation |
| GET | `/reconciliation/runs/{id}` | Reconciliation report |
| GET | `/actuator/health` | Health check |

## Testing (required)
- **Unit:** backoff calculation (incl. cap), event → action mapping, reconciliation comparison rules.
- **Integration (Testcontainers Postgres):**
  - Same webhook event sent twice → exactly one order, one sync job.
  - Invalid signature → 400, nothing written.
  - ERP fails N times then succeeds → job ends SUCCEEDED with attempts = N+1.
  - ERP always fails → job ends FAILED after max attempts.
  - Two workers running concurrently never process the same job.
  - Reconciliation detects each issue type when data is seeded to produce it.
- CI must run all tests on every push.

## Local development
```bash
cp .env.example .env              # add STRIPE_SECRET_KEY and STRIPE_WEBHOOK_SECRET (test mode)
docker compose up -d postgres
./mvnw spring-boot:run

# In another terminal (Stripe CLI, free, test mode):
stripe listen --forward-to localhost:8080/webhooks/stripe   # prints the webhook signing secret
stripe trigger payment_intent.succeeded
stripe trigger charge.refunded
```

## Build plan (one week)
| Day | Deliverable |
|---|---|
| 1 | Repo, Spring Boot skeleton, Docker Compose, Flyway V1 schema, CI running, README stub. **Push.** |
| 2 | Webhook endpoint, signature verification, idempotent event handling, order creation + refunds. Tests. |
| 3 | Mock ERP + ERP HTTP client with timeout + failure injection. |
| 4 | Sync worker: claiming with SKIP LOCKED, backoff, max attempts, stuck-job recovery, failure-queue endpoints. Tests. |
| 5 | Reconciliation job, issue detection, report endpoints. Tests. |
| 6 | README (architecture diagram, design decisions, how to run, trade-offs), cleanup. |
| 7 | Buffer. Fix bugs. Optional: start a stretch goal. |

## README must cover
- Problem statement (2–3 sentences)
- Architecture diagram + flow
- Design decisions and **why**: DB-level idempotency, sync job in the same transaction (outbox-style), SKIP LOCKED, exponential backoff, idempotent ERP writes
- How to run locally with the Stripe CLI
- Trade-offs and what would change at scale (e.g. Kafka instead of polling, partitioning, real ERP auth/rate limits)

## Stretch goals (only after core is done)
1. **Load simulator** (small script) that fires many events incl. duplicates and ERP failures, then reports: duplicates prevented, jobs retried, final consistency. These become the real resume metrics.
2. **LLM summary** of a reconciliation report in plain English for a finance audience.
3. Metrics via Actuator/Micrometer (jobs succeeded/failed, retry counts, sync latency).
