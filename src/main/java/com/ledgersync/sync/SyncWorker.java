package com.ledgersync.sync;

import com.ledgersync.config.SyncProperties;
import com.ledgersync.order.Order;
import com.ledgersync.order.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pushes queued orders and refunds to the ERP.
 *
 * <p>Safe to run on many threads or many app instances at once: jobs are claimed with
 * {@code FOR UPDATE SKIP LOCKED} (see {@link SyncJobRepository#claimDue}), so no two workers get the same job.
 * Delivery is at-least-once (a worker can die after the ERP call but before marking success), which is
 * fine because ERP writes are idempotent on external_ref.
 */
@Component
public class SyncWorker {

    private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);

    private final SyncJobRepository jobs;
    private final OrderRepository orders;
    private final ErpClient erp;
    private final SyncProperties props;
    private final Backoff backoff;
    private final MeterRegistry metrics;

    public SyncWorker(SyncJobRepository jobs, OrderRepository orders, ErpClient erp, SyncProperties props,
                      MeterRegistry metrics) {
        this.jobs = jobs;
        this.orders = orders;
        this.erp = erp;
        this.props = props;
        this.metrics = metrics;
        this.backoff = new Backoff(props.backoffBaseMs(), props.backoffCapMs(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    /** Claims one batch of due jobs and processes it. @return how many jobs were claimed */
    public int runOnce() {
        List<SyncJob> claimed = jobs.claimDue(props.batchSize());
        claimed.forEach(this::process);
        return claimed.size();
    }

    /** Requeues jobs whose worker died mid-flight. @return how many were recovered */
    public int recoverStuckJobs() {
        int recovered = jobs.resetStuck(props.stuckTimeoutMs(), props.maxAttempts());
        if (recovered > 0) {
            log.warn("Recovered {} sync job(s) stuck IN_PROGRESS", recovered);
        }
        return recovered;
    }

    private void process(SyncJob job) {
        try {
            Order order = orders.findById(job.orderId())
                    .orElseThrow(() -> new IllegalStateException("Order " + job.orderId() + " not found"));
            switch (job.jobType()) {
                case CREATE_INVOICE -> erp.createInvoice(order.paymentIntentId(), order.amountCents(), order.currency());
                // Send the order's refunded total as it is NOW. If several refunds queued several jobs, each
                // one sends the latest absolute total, so they can run in any order and still converge.
                case RECORD_REFUND -> erp.recordRefund(order.paymentIntentId(), order.refundedCents());
            }
            jobs.markSucceeded(job.id());
            count("succeeded");
        } catch (Exception e) {
            handleFailure(job, e);
        }
    }

    private void handleFailure(SyncJob job, Exception e) {
        int failures = job.attempts() + 1;
        String error = e.getClass().getSimpleName() + ": " + e.getMessage();

        if (failures >= props.maxAttempts()) {
            jobs.markFailed(job.id(), error);
            count("failed");
            log.error("Sync job {} ({}) FAILED after {} attempts: {}", job.id(), job.jobType(), failures, error);
        } else {
            long delayMs = backoff.delayMs(failures);
            jobs.markRetry(job.id(), error, delayMs);
            count("retry");
            log.warn("Sync job {} ({}) attempt {} failed, retrying in {} ms: {}",
                    job.id(), job.jobType(), failures, delayMs, error);
        }
    }

    private void count(String outcome) {
        metrics.counter("ledgersync.sync.attempts", "outcome", outcome).increment();
    }
}
