package com.ledgersync.order;

public enum OrderStatus {
    PAID,
    PARTIALLY_REFUNDED,
    REFUNDED;

    public static OrderStatus forRefund(long amountCents, long refundedCents) {
        if (refundedCents <= 0) {
            return PAID;
        }
        return refundedCents >= amountCents ? REFUNDED : PARTIALLY_REFUNDED;
    }
}
