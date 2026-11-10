# LedgerSync

![CI](https://github.com/Viraj012/ledgersync/actions/workflows/ci.yml/badge.svg)

**Keeps Stripe payments and ERP accounting records in sync, and flags when they drift apart.**

A business takes payments in Stripe, but its finance system (NetSuite, QuickBooks...) is updated separately.
Webhooks get retried, ERP calls fail, refunds get missed, and finance teams end up reconciling by hand.
LedgerSync ingests Stripe webhooks exactly once, pushes them to the ERP with retries, and runs a
reconciliation job that reports every mismatch.

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Stripe Java SDK · Testcontainers · Docker Compose · GitHub Actions

---

## Results

`scripts/simulate.py` fires 1,000 orders at the service: 845 duplicate deliveries, refunds arriving before
their payments, and an ERP that fails **30%** of calls.

```
 Webhook deliveries sent              2212   (1253/s)
   unique Stripe events               1294
   duplicate deliveries                845
   out-of-order (503, redelivered)      73
 Duplicates rejected                   845
 Orders created                       1000   (expected 1000)
   with refunds recorded               294   (expected 294)
 Duplicate orders created                0
 ERP invoices created                 1000   (expected 1000)
 ERP sync jobs                        1294
   failed ERP calls (retried)          778
   jobs that needed a retry            475
   exhausted retries -> FAILED           8   (recovered via failure queue)
 Reconciliation issues, before           7
 Reconciliation issues, after            0
 Final consistency                  100% ✔
```

## Architecture

```mermaid
flowchart LR
    Stripe([Stripe]) -- webhook --> WC[WebhookController]
    subgraph tx [one DB transaction]
        WC --> E[(stripe_events<br/>UNIQUE event_id)]
        WC --> O[(orders)]
        WC --> J[(sync_jobs<br/>PENDING)]
    end
    W[SyncWorker<br/>@Scheduled] -- "claim: FOR UPDATE<br/>SKIP LOCKED" --> J
    W -- "HTTP + timeout<br/>retry w/ backoff" --> ERP[Mock ERP<br/>/mock-erp/**]
    ERP --> R[(erp_records<br/>UNIQUE external_ref)]
    REC[ReconciliationJob<br/>daily + on demand] --> O
    REC --> R
    REC --> I[(reconciliation_runs<br/>+ issues)]
```

1. **Receive.** `POST /webhooks/stripe` verifies the Stripe signature (400 if invalid).
2. **Deduplicate and store.** In **one transaction** it inserts the event id (a duplicate stops here with 200),
   creates the order or applies the refund, and queues a `sync_job`.
3. **Sync.** A background worker claims due jobs, calls the ERP over HTTP with a timeout, and on failure
   retries with exponential backoff. After 5 attempts the job moves to the **failure queue**.
4. **Reconcile.** A scheduled job (or `POST /reconciliation/runs`) compares both sides and records every
   `MISSING_IN_ERP`, `AMOUNT_MISMATCH`, `REFUND_NOT_SYNCED` and `ORPHAN_IN_ERP`.

The "ERP" is a mock inside the same app (`/mock-erp/**`), with configurable failure rate and latency. The
worker still talks to it over real HTTP, exactly as it would to a third-party API.

## Run it

Needs **Docker**. Java 21 is only needed to run the app outside Docker or to run the tests.

```bash
docker compose up --build          # Postgres + app on http://localhost:8080
curl localhost:8080/actuator/health
```

### See it work (5-minute demo)

```bash
# 1. Load test: duplicates, out-of-order refunds, 30% ERP failures. Prints the report above.
python3 scripts/simulate.py

# 2. Live numbers
curl -s localhost:8080/stats | python3 -m json.tool

# 3. Make the ERP flaky and watch retries in the app logs
curl -X PUT localhost:8080/mock-erp/config -H 'Content-Type: application/json' -d '{"failureRate":0.5,"latencyMs":0}'
python3 scripts/simulate.py --orders 50 --erp-failure-rate 0.5

# 4. Inspect the failure queue and requeue a job
curl -s 'localhost:8080/sync-jobs?status=FAILED' | python3 -m json.tool
curl -X POST localhost:8080/sync-jobs/<id>/retry

# 5. Tamper with the ERP behind our back, then reconcile: it reports AMOUNT_MISMATCH
docker compose exec postgres psql -U ledgersync -c \
  "UPDATE erp_records SET amount_cents = amount_cents - 100 WHERE id = (SELECT min(id) FROM erp_records);"
curl -s -X POST localhost:8080/reconciliation/runs | python3 -m json.tool
```

### With real Stripe (test mode)

```bash
cp .env.example .env
stripe listen --forward-to localhost:8080/webhooks/stripe   # prints whsec_...; put it in .env
docker compose up --build                                   # restart so the app picks up the secret
stripe trigger payment_intent.succeeded
stripe trigger charge.refunded
curl localhost:8080/orders/1
```

### Develop locally

```bash
cp .env.example .env
docker compose up -d postgres
./mvnw spring-boot:run      # reads .env automatically
./mvnw verify               # all tests (needs Docker for Testcontainers)
```

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/webhooks/stripe` | Stripe webhook receiver |
| GET | `/orders/{id}` | Order + its sync jobs |
| GET | `/sync-jobs?status=FAILED` | Failure queue (any status works) |
| POST | `/sync-jobs/{id}/retry` | Requeue a FAILED job (409 if it isn't failed) |
| POST | `/reconciliation/runs` | Run reconciliation now |
| GET | `/reconciliation/runs` / `/reconciliation/runs/{id}` | Recent runs / one report |
| GET | `/stats` | Counts for demos and the simulator |
| GET/PUT | `/mock-erp/config` | Mock ERP failure rate and latency, live |
| GET | `/actuator/health`, `/actuator/metrics` | Health and Micrometer metrics |

## Design decisions

**Idempotency enforced by the database, not app code.** `stripe_events.event_id` is `UNIQUE`, and
the handler starts with `INSERT ... ON CONFLICT (event_id) DO NOTHING`. Zero rows inserted means a duplicate,
so we return 200 and do nothing. An in-memory "seen" check would race. With the constraint, two concurrent
deliveries of the same event serialize on the row lock, and the loser sees the winner's row (there's a test
that fires 16 concurrent duplicates). `ON CONFLICT` rather than catching the unique violation, because in
Postgres a failed statement aborts the whole transaction. `orders.payment_intent_id` is also `UNIQUE` as a
second safety net.

**Sync job in the same transaction (transactional outbox).** The event row, the order change and the
`sync_jobs` row commit together or not at all. Calling the ERP inside the webhook would make Stripe wait on a
slow third party, and a crash between "saved order" and "called ERP" would lose the sync silently.

**Out-of-order events.** Stripe doesn't guarantee ordering. A refund for an unknown payment returns 503 and
the transaction rolls back, so the event isn't marked as seen and Stripe's redelivery is processed normally.
Stripe sends refund totals as cumulative amounts, so we keep `max(current, incoming)`. A stale event can't
roll the total back.

**`FOR UPDATE SKIP LOCKED` job claiming.** One `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)
RETURNING *` claims a batch and marks it `IN_PROGRESS` in autocommit. So the claim commits *before* the
slow ERP call, and no DB transaction stays open across the network. Any number of worker threads or app
instances can poll together without double-processing (tested with two concurrent workers on 40 jobs).
This gives the job-queue guarantees of a broker using only Postgres.

**Exponential backoff with jitter and a cap.** `base * 2^(failures-1)` (5s, 10s, 20s, 40s...), +0–10% jitter,
capped at 10 minutes. Jitter stops a burst of jobs that failed together from hitting the recovering ERP in
the same second. After `max-attempts` (5) the job is `FAILED` with its `last_error`: the failure queue, which
can be requeued from the API.

**Idempotent ERP writes.** Delivery to the ERP is *at-least-once*: a worker can time out after the ERP wrote
but before it answered, or crash before marking success. So ERP writes are made safe to repeat:
`erp_records.external_ref` (= PaymentIntent id) is `UNIQUE`, and refund sync sends the **absolute** refunded
total, never a delta. A test injects latency above the client timeout, so the ERP writes but the worker
sees a failure. It checks that the retry creates no second invoice.

**Dependent jobs wait their turn.** A refund can only be recorded on an invoice the ERP already has. The
claim query skips a `RECORD_REFUND` job while its order's `CREATE_INVOICE` is still pending or in
progress, so it doesn't burn retry attempts on 404s while the invoice is still retrying (before this, a 30%
failure run produced dozens of 404s and refund jobs ran out of attempts early; now it's zero). If the
invoice job gives up, the refund is released so it fails visibly into the failure queue too, instead of
waiting forever.

**Stuck-job recovery.** Jobs left `IN_PROGRESS` past a timeout (the worker died) are requeued, and that
counts as an attempt, so a job that kills workers can't loop forever.

**Consistent reconciliation snapshot.** The run executes in a `REPEATABLE READ` transaction, so orders, ERP
records and in-flight jobs are all read from the same snapshot. Orders with a sync still in flight aren't
flagged. If that sync later fails, the next run reports it.

**JdbcTemplate/JdbcClient over JPA.** The interesting parts *are* the SQL (`ON CONFLICT`, `SKIP LOCKED`,
`RETURNING`, row locks). Keeping them explicit makes the guarantees visible and reviewable.

## Testing

`./mvnw verify` runs **49 tests**. Integration tests use a real Postgres 16 via Testcontainers, not H2.

| Area | What's proven |
|---|---|
| Webhooks | same event twice gives 1 order and 1 job · 16 concurrent duplicates give 1 order · bad/missing/tampered signature gives 400 with nothing written · unknown types stored as IGNORED · full/partial refunds · late stale refund ignored · refund-before-payment gives 503 and later succeeds |
| Sync worker | ERP fails N times then succeeds, so attempts = N+1 · always fails, so FAILED after 5 · backoff schedules in the future · two concurrent workers never process the same job · refund sync waits for its invoice, released if the invoice gave up · stuck jobs recovered · failure queue list/retry/409/404 |
| End to end (real HTTP) | payment → invoice → refund → reconciliation clean · 503 outage retried · timeout-after-write retried with no duplicate invoice · runtime fault config |
| Reconciliation | every issue type detected from seeded data · in-flight syncs not flagged · rule unit tests |
| Unit | backoff math incl. cap, jitter and overflow · Stripe event → action mapping |

CI (GitHub Actions) runs the full suite and builds the Docker image on every push and PR.

## Trade-offs and what changes at scale

- **Polling vs a broker.** Polling Postgres every second is simple and transactional with the data. At
  high volume: Kafka/SQS fed from the outbox via CDC (Debezium), partitioned by `payment_intent_id`
  to keep per-order ordering.
- **Reconciliation loads everything into memory.** Fine for thousands of orders. At scale: a
  SQL `FULL OUTER JOIN` streamed in batches, incremental by `updated_at` window, partitioned by date.
- **Table growth.** `stripe_events` and `sync_jobs` grow forever. Add retention (partition by month and
  drop old partitions; Stripe only retries for ~3 days).
- **Stale worker writes after recovery.** If a "stuck" worker comes back after its job was requeued and
  reclaimed, both may call the ERP. Idempotent ERP writes make that harmless. A claim token/version column
  would make it impossible.
- **Real ERP.** OAuth/token refresh, rate limits (respect `Retry-After`, token-bucket the worker), a
  circuit breaker so an outage doesn't burn every job's attempts, and mapping to the ERP's own invoice
  ids instead of reusing the Stripe id.
- **Operations.** Alerting on failure-queue depth and reconciliation issues (Micrometer counters are
  already exported at `/actuator/metrics`), and an LLM-written summary of each reconciliation report for
  finance.

## Project layout

```
src/main/java/com/ledgersync
  webhook/         Stripe signature check, event mapping, idempotent ingestion
  order/           orders table + GET /orders/{id}
  sync/            sync_jobs, SyncWorker, Backoff, ErpClient, failure-queue API
  erp/             mock ERP (another company's system, simulated)
  reconciliation/  comparison rules, runs, reports, scheduler
  stats/           /stats for demos
  config/          typed configuration
src/main/resources/db/migration   Flyway schema
scripts/simulate.py               load simulator
```
