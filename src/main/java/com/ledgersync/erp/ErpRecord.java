package com.ledgersync.erp;

import java.time.OffsetDateTime;

public record ErpRecord(
        long id,
        String externalRef,
        long amountCents,
        long refundedCents,
        String currency,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
