package com.ledgersync.webhook;

import static com.ledgersync.StripeTestEvents.chargeRefunded;
import static com.ledgersync.StripeTestEvents.event;
import static com.ledgersync.StripeTestEvents.paymentSucceeded;
import static com.ledgersync.StripeTestEvents.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgersync.IntegrationTest;
import com.ledgersync.order.Order;
import com.ledgersync.order.OrderRepository;
import com.ledgersync.order.OrderStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class WebhookIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    WebhookService webhookService;

    @Autowired
    OrderRepository orders;

    @Test
    void sameEventTwiceCreatesExactlyOneOrderAndOneSyncJob() throws Exception {
        String payload = paymentSucceeded("evt_1", "pi_1", 5000);

        send(payload, sign(payload)).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("processed"));
        send(payload, sign(payload)).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("duplicate"));

        assertThat(count("stripe_events")).isEqualTo(1);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("sync_jobs")).isEqualTo(1);
        Order order = orders.findById(1).orElseThrow();
        assertThat(order.paymentIntentId()).isEqualTo("pi_1");
        assertThat(order.amountCents()).isEqualTo(5000);
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void concurrentDuplicateDeliveriesStillCreateOneOrder() throws Exception {
        StripeAction payment = new StripeAction.PaymentSucceeded("pi_race", 1000, "usd", null);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<WebhookResult>> deliveries = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            deliveries.add(() -> webhookService.handle("evt_race", "payment_intent.succeeded", payment));
        }

        List<WebhookResult> results = new ArrayList<>();
        for (Future<WebhookResult> f : pool.invokeAll(deliveries)) {
            results.add(f.get());
        }
        pool.shutdown();

        assertThat(results).filteredOn(r -> r == WebhookResult.PROCESSED).hasSize(1);
        assertThat(results).filteredOn(r -> r == WebhookResult.DUPLICATE).hasSize(15);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("sync_jobs")).isEqualTo(1);
    }

    @Test
    void invalidSignatureIsRejectedAndNothingIsWritten() throws Exception {
        String payload = paymentSucceeded("evt_1", "pi_1", 5000);

        send(payload, sign(payload, "whsec_wrong_secret")).andExpect(status().isBadRequest());
        send(payload, "t=123,v1=deadbeef").andExpect(status().isBadRequest());

        assertThat(count("stripe_events")).isZero();
        assertThat(count("orders")).isZero();
        assertThat(count("sync_jobs")).isZero();
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        mvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .content(paymentSucceeded("evt_1", "pi_1", 5000)))
                .andExpect(status().isBadRequest());

        assertThat(count("stripe_events")).isZero();
    }

    @Test
    void tamperedPayloadIsRejected() throws Exception {
        String original = paymentSucceeded("evt_1", "pi_1", 5000);
        String tampered = paymentSucceeded("evt_1", "pi_1", 1);

        send(tampered, sign(original)).andExpect(status().isBadRequest());

        assertThat(count("orders")).isZero();
    }

    @Test
    void unhandledEventTypeIsStoredAsIgnored() throws Exception {
        String payload = event("evt_other", "customer.created");

        send(payload, sign(payload)).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ignored"));

        assertThat(jdbc.sql("SELECT status FROM stripe_events WHERE event_id = 'evt_other'").query(String.class).single())
                .isEqualTo("IGNORED");
        assertThat(count("orders")).isZero();
    }

    @Test
    void fullRefundMarksOrderRefundedAndQueuesRefundSync() throws Exception {
        sendSigned(paymentSucceeded("evt_pay", "pi_1", 5000));

        sendSigned(chargeRefunded("evt_ref", "pi_1", 5000)).andExpect(status().isOk());

        Order order = orders.findById(1).orElseThrow();
        assertThat(order.refundedCents()).isEqualTo(5000);
        assertThat(order.status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(jdbc.sql("SELECT job_type FROM sync_jobs ORDER BY id").query(String.class).list())
                .containsExactly("CREATE_INVOICE", "RECORD_REFUND");
    }

    @Test
    void partialRefundMarksOrderPartiallyRefunded() throws Exception {
        sendSigned(paymentSucceeded("evt_pay", "pi_1", 5000));

        sendSigned(chargeRefunded("evt_ref", "pi_1", 1500));

        Order order = orders.findById(1).orElseThrow();
        assertThat(order.refundedCents()).isEqualTo(1500);
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void lateArrivingOlderRefundEventDoesNotRollBackTheTotal() throws Exception {
        sendSigned(paymentSucceeded("evt_pay", "pi_1", 5000));
        sendSigned(chargeRefunded("evt_ref_2", "pi_1", 3000)); // newer: cumulative 3000
        sendSigned(chargeRefunded("evt_ref_1", "pi_1", 1000)); // older, delivered late

        assertThat(orders.findById(1).orElseThrow().refundedCents()).isEqualTo(3000);
        assertThat(count("sync_jobs")).isEqualTo(2); // invoice + one refund; the stale one queued nothing
    }

    @Test
    void refundBeforePaymentAsksStripeToRetryAndRecordsNothing() throws Exception {
        String refund = chargeRefunded("evt_ref", "pi_1", 1000);

        send(refund, sign(refund)).andExpect(status().isServiceUnavailable());
        assertThat(count("stripe_events")).isZero(); // rolled back, so the redelivery isn't a "duplicate"

        sendSigned(paymentSucceeded("evt_pay", "pi_1", 5000));
        send(refund, sign(refund)).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("processed"));

        assertThat(orders.findById(1).orElseThrow().refundedCents()).isEqualTo(1000);
    }

    private ResultActions sendSigned(String payload) throws Exception {
        return send(payload, sign(payload));
    }

    private ResultActions send(String payload, String signature) throws Exception {
        return mvc.perform(post("/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", signature)
                .content(payload));
    }
}
