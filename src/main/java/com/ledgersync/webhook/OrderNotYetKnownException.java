package com.ledgersync.webhook;

/**
 * A refund arrived before the payment it refunds (Stripe doesn't guarantee event order).
 * We answer 503 so Stripe redelivers later. The transaction rolls back, so the event isn't recorded
 * and the redelivery won't be mistaken for a duplicate.
 */
public class OrderNotYetKnownException extends RuntimeException {

    public OrderNotYetKnownException(String paymentIntentId) {
        super("No order yet for PaymentIntent " + paymentIntentId + "; asking Stripe to retry");
    }
}
