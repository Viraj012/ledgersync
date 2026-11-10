-- Lookups by order: the "is this order's invoice synced yet?" check when claiming refund jobs,
-- GET /orders/{id}, and reconciliation's "which orders still have a sync in flight?".
CREATE INDEX idx_sync_jobs_order ON sync_jobs (order_id, job_type, status);
