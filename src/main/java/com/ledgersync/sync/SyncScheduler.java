package com.ledgersync.sync;

import com.ledgersync.config.SyncProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives {@link SyncWorker} on a timer. Disabled in tests, which call the worker directly. */
@Component
@ConditionalOnProperty(name = "sync.worker.enabled", havingValue = "true", matchIfMissing = true)
class SyncScheduler {

    private final SyncWorker worker;
    private final SyncProperties props;

    SyncScheduler(SyncWorker worker, SyncProperties props) {
        this.worker = worker;
        this.props = props;
    }

    /** Keeps pulling batches while they come back full, so a backlog drains without waiting a tick per batch. */
    @Scheduled(fixedDelayString = "${sync.worker.fixed-delay-ms}")
    void poll() {
        while (worker.runOnce() == props.batchSize()) {
            // keep draining
        }
    }

    @Scheduled(fixedDelayString = "${sync.worker.stuck-check-ms:30000}")
    void recoverStuck() {
        worker.recoverStuckJobs();
    }
}
