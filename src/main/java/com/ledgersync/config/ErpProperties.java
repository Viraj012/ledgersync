package com.ledgersync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl   where the ERP client sends requests (the mock ERP lives inside this app)
 * @param timeoutMs connect + read timeout for each ERP call
 * @param mock      fault injection for the mock ERP, to simulate a flaky external system
 */
@ConfigurationProperties("erp")
public record ErpProperties(String baseUrl, long timeoutMs, Mock mock) {

    public record Mock(double failureRate, long latencyMs) {
    }
}
