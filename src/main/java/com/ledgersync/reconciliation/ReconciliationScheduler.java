package com.ledgersync.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "reconciliation.scheduler.enabled", havingValue = "true", matchIfMissing = true)
class ReconciliationScheduler {

    private final ReconciliationService service;

    ReconciliationScheduler(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${reconciliation.cron}")
    void runDaily() {
        service.run();
    }
}
