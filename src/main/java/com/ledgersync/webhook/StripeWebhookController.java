package com.ledgersync.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgersync.config.StripeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final StripeProperties stripe;
    private final StripeEventMapper mapper;
    private final WebhookService service;
    private final ObjectMapper json;
    private final MeterRegistry metrics;

    public StripeWebhookController(StripeProperties stripe, StripeEventMapper mapper, WebhookService service,
                                   ObjectMapper json, MeterRegistry metrics) {
        this.stripe = stripe;
        this.mapper = mapper;
        this.service = service;
        this.json = json;
        this.metrics = metrics;
    }

    /** Raw bytes, not a parsed object: the signature is over the exact payload Stripe sent. */
    @PostMapping("/webhooks/stripe")
    public ResponseEntity<Map<String, String>> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {

        String payload = new String(body, StandardCharsets.UTF_8);
        Event event;
        try {
            if (signature == null) {
                throw new SignatureVerificationException("Missing Stripe-Signature header", null);
            }
            // Verifies the HMAC and the timestamp (rejects replays older than 5 minutes), then parses.
            event = Webhook.constructEvent(payload, signature, stripe.webhookSecret());
        } catch (SignatureVerificationException e) {
            count("invalid_signature");
            return ResponseEntity.badRequest().body(Map.of("error", "invalid signature"));
        } catch (RuntimeException e) {
            // Signature was fine but the body isn't a Stripe event (the SDK throws Gson parse errors).
            throw new IllegalArgumentException("Payload is not a valid Stripe event", e);
        }

        StripeAction action = mapper.map(event.getType(), dataObject(payload));
        WebhookResult result = service.handle(event.getId(), event.getType(), action);
        count(result.name().toLowerCase());
        log.info("Stripe event {} ({}) -> {}", event.getId(), event.getType(), result);
        return ResponseEntity.ok(Map.of("result", result.name().toLowerCase()));
    }

    @ExceptionHandler(OrderNotYetKnownException.class)
    ResponseEntity<Map<String, String>> outOfOrder(OrderNotYetKnownException e) {
        count("deferred");
        log.info(e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> malformed(IllegalArgumentException e) {
        count("malformed");
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private JsonNode dataObject(String payload) {
        try {
            return json.readTree(payload).path("data").path("object");
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload is not valid JSON", e);
        }
    }

    private void count(String result) {
        metrics.counter("ledgersync.webhook.events", "result", result).increment();
    }
}
