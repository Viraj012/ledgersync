package com.ledgersync.sync;

import com.ledgersync.config.ErpProperties;
import com.ledgersync.erp.ErpApi;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the ERP. Every call has a connect + read timeout; a hung ERP must never hang a worker.
 * Any non-2xx or I/O error surfaces as a RestClientException, which the worker turns into a retry.
 */
@Component
public class ErpClient {

    private final RestClient http;

    public ErpClient(RestClient.Builder builder, ErpProperties props) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(props.timeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(props.timeoutMs()));
        this.http = builder.baseUrl(props.baseUrl()).requestFactory(requestFactory).build();
    }

    public void createInvoice(String externalRef, long amountCents, String currency) {
        http.post()
                .uri("/mock-erp/invoices")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErpApi.CreateInvoiceRequest(externalRef, amountCents, currency))
                .retrieve()
                .toBodilessEntity();
    }

    public void recordRefund(String externalRef, long refundedCents) {
        http.post()
                .uri("/mock-erp/invoices/{ref}/refunds", externalRef)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErpApi.RecordRefundRequest(refundedCents))
                .retrieve()
                .toBodilessEntity();
    }
}
