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
