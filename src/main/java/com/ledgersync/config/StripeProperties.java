package com.ledgersync.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Stripe settings. The webhook secret is the only thing we need: we verify, we never call Stripe. */
@Validated
@ConfigurationProperties("stripe")
public record StripeProperties(@NotBlank String webhookSecret) {
}
