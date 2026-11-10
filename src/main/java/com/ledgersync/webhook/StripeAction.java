package com.ledgersync.webhook;

/** What a Stripe event means for us, independent of Stripe's JSON shape. */
public sealed interface StripeAction {

    record PaymentSucceeded(String paymentIntentId, long amountCents, String currency, String customerEmail)
            implements StripeAction {
    }

    /** {@code amountRefundedCents} is Stripe's cumulative total for the charge, not this refund's delta. */
    record ChargeRefunded(String paymentIntentId, long amountRefundedCents) implements StripeAction {
    }

    record Ignored() implements StripeAction {
    }
}
