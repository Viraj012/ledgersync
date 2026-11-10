package com.ledgersync;

import com.stripe.net.Webhook;
import java.time.Instant;

/** Builds Stripe-shaped webhook payloads and valid Stripe-Signature headers for tests. */
public final class StripeTestEvents {

    public static final String SECRET = "whsec_test_secret";

    private StripeTestEvents() {
    }

    public static String paymentSucceeded(String eventId, String paymentIntentId, long amountCents) {
        return """
                {"id":"%s","object":"event","type":"payment_intent.succeeded",
                 "data":{"object":{"id":"%s","object":"payment_intent","amount":%d,"amount_received":%d,
                                   "currency":"usd","receipt_email":"buyer@example.com"}}}
                """.formatted(eventId, paymentIntentId, amountCents, amountCents);
    }

    public static String chargeRefunded(String eventId, String paymentIntentId, long amountRefundedCents) {
        return """
                {"id":"%s","object":"event","type":"charge.refunded",
                 "data":{"object":{"id":"ch_%s","object":"charge","payment_intent":"%s",
                                   "amount_refunded":%d,"currency":"usd"}}}
                """.formatted(eventId, eventId, paymentIntentId, amountRefundedCents);
    }

    public static String event(String eventId, String type) {
        return """
                {"id":"%s","object":"event","type":"%s","data":{"object":{"id":"obj_1"}}}
                """.formatted(eventId, type);
    }

    /** Same scheme Stripe uses: HMAC-SHA256 over "{timestamp}.{payload}". */
    public static String sign(String payload, String secret) {
        long timestamp = Instant.now().getEpochSecond();
        try {
            String signature = Webhook.Util.computeHmacSha256(secret, timestamp + "." + payload);
            return "t=" + timestamp + ",v1=" + signature;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sign(String payload) {
        return sign(payload, SECRET);
    }
}
