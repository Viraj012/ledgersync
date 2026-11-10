package com.ledgersync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("sync.worker")
public record SyncProperties(
        boolean enabled,
        long fixedDelayMs,
        int batchSize,
        int maxAttempts,
        long backoffBaseMs,
        long backoffCapMs,
        long stuckTimeoutMs) {
}
