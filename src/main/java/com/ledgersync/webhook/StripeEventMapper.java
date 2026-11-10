package com.ledgersync.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Maps an event type + its {@code data.object} JSON to a {@link StripeAction}.
 *
 * <p>We read the raw JSON instead of the Stripe SDK's typed objects so a Stripe API version mismatch
 * can't silently turn a payment into "no data".
 */
@Component
public class StripeEventMapper {

    public StripeAction map(String eventType, JsonNode object) {
        return switch (eventType) {
            case "payment_intent.succeeded" -> paymentSucceeded(object);
            case "charge.refunded" -> chargeRefunded(object);
            default -> new StripeAction.Ignored();
        };
    }

    private StripeAction paymentSucceeded(JsonNode pi) {
        String id = requireText(pi, "id");
        // amount_received is what was actually captured; fall back to amount for older payloads.
        long amount = pi.hasNonNull("amount_received") ? pi.get("amount_received").asLong() : pi.path("amount").asLong();
        String currency = requireText(pi, "currency").toLowerCase(Locale.ROOT);
        String email = pi.hasNonNull("receipt_email") ? pi.get("receipt_email").asText() : null;
        return new StripeAction.PaymentSucceeded(id, amount, currency, email);
    }

    private StripeAction chargeRefunded(JsonNode charge) {
        if (!charge.hasNonNull("payment_intent")) {
            // A charge made without a PaymentIntent never created an order here, so there's nothing to refund.
            return new StripeAction.Ignored();
        }
        return new StripeAction.ChargeRefunded(
                charge.get("payment_intent").asText(),
                charge.path("amount_refunded").asLong());
    }

    private static String requireText(JsonNode node, String field) {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw new IllegalArgumentException("Stripe event is missing data.object." + field);
        }
        return node.get(field).asText();
    }
}
