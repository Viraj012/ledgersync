package com.ledgersync.sync;

import java.time.OffsetDateTime;

public record SyncJob(
        long id,
        long orderId,
        JobType jobType,
        JobStatus status,
        int attempts,
        OffsetDateTime nextAttemptAt,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
