package com.ledgersync.reconciliation;

import java.time.OffsetDateTime;

public record ReconciliationIssue(
        long id,
        long runId,
        Long orderId,
        String externalRef,
        IssueType issueType,
        String details,
        OffsetDateTime createdAt) {
}
