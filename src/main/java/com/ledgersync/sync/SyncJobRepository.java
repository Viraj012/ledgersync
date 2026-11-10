package com.ledgersync.sync;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SyncJobRepository {

    private final JdbcClient jdbc;

    public SyncJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long orderId, JobType type) {
        return jdbc.sql("""
                        INSERT INTO sync_jobs (order_id, job_type, status)
                        VALUES (:orderId, :type, 'PENDING')
                        RETURNING id
                        """)
                .param("orderId", orderId)
                .param("type", type.name())
                .query(Long.class)
                .single();
    }

    /**
     * Atomically claims up to {@code limit} due jobs and marks them IN_PROGRESS.
     *
     * <p>One statement, run in autocommit, so the claim is committed <em>before</em> we call the ERP.
     * We never hold a database transaction open across a slow network call.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}: rows another worker is claiming right now are skipped instead of
     * waited on, so concurrent workers each get different jobs. If another worker committed its claim
     * first, Postgres re-checks the row, sees it's no longer PENDING, and leaves it out.
     *
     * <p>A refund can only be recorded on an invoice the ERP already has. So a RECORD_REFUND job isn't
     * claimable while its order's CREATE_INVOICE is still PENDING / IN_PROGRESS; otherwise it would burn
     * attempts on 404s. If the invoice job FAILED, the refund is released so it lands in the failure queue
     * too, instead of waiting silently forever.
     */
    public List<SyncJob> claimDue(int limit) {
        return jdbc.sql("""
                        UPDATE sync_jobs
                           SET status = 'IN_PROGRESS', updated_at = now()
                         WHERE status = 'PENDING'
                           AND id IN (SELECT job.id
                                        FROM sync_jobs job
                                       WHERE job.status = 'PENDING' AND job.next_attempt_at <= now()
                                         AND NOT (job.job_type = 'RECORD_REFUND' AND EXISTS (
                                                  SELECT 1 FROM sync_jobs invoice
                                                   WHERE invoice.order_id = job.order_id
                                                     AND invoice.job_type = 'CREATE_INVOICE'
                                                     AND invoice.status IN ('PENDING', 'IN_PROGRESS')))
                                       ORDER BY job.next_attempt_at
                                       LIMIT :limit
                                         FOR UPDATE OF job SKIP LOCKED)
                        RETURNING *
                        """)
                .param("limit", limit)
                .query(SyncJob.class)
                .list();
    }

    public void markSucceeded(long id) {
        jdbc.sql("""
                        UPDATE sync_jobs
                           SET status = 'SUCCEEDED', attempts = attempts + 1, last_error = NULL, updated_at = now()
                         WHERE id = :id AND status = 'IN_PROGRESS'
                        """)
                .param("id", id)
                .update();
    }

    /** Failed but has attempts left: back to PENDING, not due again until the backoff has passed. */
    public void markRetry(long id, String error, long delayMs) {
        jdbc.sql("""
                        UPDATE sync_jobs
                           SET status = 'PENDING',
                               attempts = attempts + 1,
                               last_error = :error,
                               next_attempt_at = now() + (:delayMs * interval '1 millisecond'),
                               updated_at = now()
                         WHERE id = :id AND status = 'IN_PROGRESS'
                        """)
                .param("error", error)
                .param("delayMs", delayMs)
                .param("id", id)
                .update();
    }

    /** Out of attempts: park it in the failure queue with the last error for a human to look at. */
    public void markFailed(long id, String error) {
        jdbc.sql("""
                        UPDATE sync_jobs
                           SET status = 'FAILED', attempts = attempts + 1, last_error = :error, updated_at = now()
                         WHERE id = :id AND status = 'IN_PROGRESS'
                        """)
                .param("error", error)
                .param("id", id)
                .update();
    }

    /**
     * Jobs left IN_PROGRESS longer than the timeout belonged to a worker that crashed or hung.
     * Requeue them, counting it as a failed attempt so a job that kills workers can't loop forever.
     */
    public int resetStuck(long timeoutMs, int maxAttempts) {
        return jdbc.sql("""
                        UPDATE sync_jobs
                           SET attempts = attempts + 1,
                               status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                               last_error = 'Recovered: stuck IN_PROGRESS past timeout',
                               next_attempt_at = now(),
                               updated_at = now()
                         WHERE status = 'IN_PROGRESS'
                           AND updated_at < now() - (:timeoutMs * interval '1 millisecond')
                        """)
                .param("maxAttempts", maxAttempts)
                .param("timeoutMs", timeoutMs)
                .update();
    }

    /** Manual retry from the failure queue: fresh attempt budget, due immediately. */
    public boolean requeueFailed(long id) {
        return jdbc.sql("""
                        UPDATE sync_jobs
                           SET status = 'PENDING', attempts = 0, next_attempt_at = now(), updated_at = now()
                         WHERE id = :id AND status = 'FAILED'
                        """)
                .param("id", id)
                .update() == 1;
    }

    public Optional<SyncJob> findById(long id) {
        return jdbc.sql("SELECT * FROM sync_jobs WHERE id = :id")
                .param("id", id)
                .query(SyncJob.class)
                .optional();
    }

    public List<SyncJob> findByOrderId(long orderId) {
        return jdbc.sql("SELECT * FROM sync_jobs WHERE order_id = :orderId ORDER BY id")
                .param("orderId", orderId)
                .query(SyncJob.class)
                .list();
    }

    public List<SyncJob> findRecent(JobStatus status, int limit) {
        if (status == null) {
            return jdbc.sql("SELECT * FROM sync_jobs ORDER BY id DESC LIMIT :limit")
                    .param("limit", limit)
                    .query(SyncJob.class)
                    .list();
        }
        return jdbc.sql("SELECT * FROM sync_jobs WHERE status = :status ORDER BY id DESC LIMIT :limit")
                .param("status", status.name())
                .param("limit", limit)
                .query(SyncJob.class)
                .list();
    }

    /** Orders whose ERP sync hasn't finished yet; reconciliation shouldn't flag these as drift. */
    public Set<Long> orderIdsWithOpenJobs() {
        return new HashSet<>(jdbc.sql("SELECT DISTINCT order_id FROM sync_jobs WHERE status IN ('PENDING', 'IN_PROGRESS')")
                .query(Long.class)
                .list());
    }
}
