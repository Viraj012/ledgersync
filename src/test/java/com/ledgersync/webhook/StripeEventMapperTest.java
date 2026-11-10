package com.ledgersync.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StripeEventMapperTest {

    private final StripeEventMapper mapper = new StripeEventMapper();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void paymentIntentSucceededBecomesPayment() throws Exception {
        JsonNode pi = json.readTree("""
                {"id":"pi_1","amount":5000,"amount_received":4500,"currency":"USD","receipt_email":"a@b.com"}
                """);

        assertThat(mapper.map("payment_intent.succeeded", pi))
                .isEqualTo(new StripeAction.PaymentSucceeded("pi_1", 4500, "usd", "a@b.com"));
    }

    @Test
    void paymentFallsBackToAmountAndAllowsMissingEmail() throws Exception {
        JsonNode pi = json.readTree("""
                {"id":"pi_1","amount":5000,"currency":"eur"}
                """);

        assertThat(mapper.map("payment_intent.succeeded", pi))
                .isEqualTo(new StripeAction.PaymentSucceeded("pi_1", 5000, "eur", null));
    }

    @Test
    void chargeRefundedBecomesRefundWithCumulativeAmount() throws Exception {
        JsonNode charge = json.readTree("""
                {"id":"ch_1","payment_intent":"pi_1","amount_refunded":1200}
                """);

        assertThat(mapper.map("charge.refunded", charge))
                .isEqualTo(new StripeAction.ChargeRefunded("pi_1", 1200));
    }

    @Test
    void refundOfChargeWithoutPaymentIntentIsIgnored() throws Exception {
        JsonNode charge = json.readTree("""
                {"id":"ch_1","payment_intent":null,"amount_refunded":1200}
                """);

        assertThat(mapper.map("charge.refunded", charge)).isInstanceOf(StripeAction.Ignored.class);
    }

    @Test
    void unknownEventTypesAreIgnored() throws Exception {
        assertThat(mapper.map("customer.created", json.readTree("{}"))).isInstanceOf(StripeAction.Ignored.class);
    }

    @Test
    void paymentWithoutIdIsRejected() throws Exception {
        assertThatThrownBy(() -> mapper.map("payment_intent.succeeded", json.readTree("{\"currency\":\"usd\"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
