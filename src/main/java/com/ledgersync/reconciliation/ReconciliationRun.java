package com.ledgersync.reconciliation;

import java.time.OffsetDateTime;

public record ReconciliationRun(
        long id,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        int ordersChecked,
        int issuesFound) {
}
