package com.ledgersync.order;

import java.time.OffsetDateTime;

public record Order(
        long id,
        String paymentIntentId,
        String customerEmail,
        long amountCents,
        String currency,
        long refundedCents,
        OrderStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
