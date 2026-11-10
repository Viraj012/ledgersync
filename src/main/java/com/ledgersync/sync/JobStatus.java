package com.ledgersync.sync;

public enum JobStatus {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    /** Gave up after max attempts. This is the failure queue; see POST /sync-jobs/{id}/retry. */
    FAILED
}
