package com.ledgersync;

import static com.ledgersync.StripeTestEvents.chargeRefunded;
import static com.ledgersync.StripeTestEvents.paymentSucceeded;
import static com.ledgersync.StripeTestEvents.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgersync.erp.ErpApi;
import com.ledgersync.erp.ErpRecord;
import com.ledgersync.erp.ErpRecordRepository;
import com.ledgersync.erp.MockErpFaults;
import com.ledgersync.reconciliation.ReconciliationService;
import com.ledgersync.sync.ErpClient;
import com.ledgersync.sync.JobStatus;
import com.ledgersync.sync.SyncJob;
import com.ledgersync.sync.SyncJobRepository;
import com.ledgersync.sync.SyncWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Whole pipeline with a real HTTP server: webhook -> order + job -> worker -> ERP client -> HTTP -> mock ERP
 * -> reconciliation. Nothing is mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = "erp.timeout-ms=500")
class EndToEndTest extends IntegrationTest {

    @DynamicPropertySource
    static void serverPort(DynamicPropertyRegistry registry) {
        int port = TestSocketUtils.findAvailableTcpPort();
        registry.add("server.port", () -> port); // erp.base-url defaults to http://localhost:${server.port}
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    SyncWorker worker;

    @Autowired
    SyncJobRepository jobs;

    @Autowired
    ErpRecordRepository erpRecords;

    @Autowired
    ErpClient erpClient;

    @Autowired
    MockErpFaults faults;

    @Autowired
    ReconciliationService reconciliation;

    @AfterEach
    void calmTheErp() {
        faults.update(new ErpApi.FaultSettings(0.0, 0));
    }

    @Test
    void paymentAndRefundFlowEndToEnd() throws Exception {
        webhook(paymentSucceeded("evt_pay", "pi_e2e", 7500));
        worker.runOnce();

        ErpRecord invoice = erpRecords.findByRef("pi_e2e").orElseThrow();
        assertThat(invoice.amountCents()).isEqualTo(7500);
        assertThat(invoice.currency()).isEqualTo("usd");

        webhook(chargeRefunded("evt_ref", "pi_e2e", 2500));
        worker.runOnce();

        assertThat(erpRecords.findByRef("pi_e2e").orElseThrow().refundedCents()).isEqualTo(2500);
        mvc.perform(get("/orders/1"))
                .andExpect(jsonPath("$.order.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.syncJobs[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.syncJobs[1].status").value("SUCCEEDED"));
        assertThat(reconciliation.run().issues()).isEmpty();
    }

    @Test
    void erpOutageIsRetriedThenSucceeds() throws Exception {
        webhook(paymentSucceeded("evt_pay", "pi_flaky", 1000));
        faults.update(new ErpApi.FaultSettings(1.0, 0)); // every call fails with 503

        worker.runOnce();

        SyncJob job = jobs.findById(1).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.PENDING);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.lastError()).contains("503");
        assertThat(erpRecords.findByRef("pi_flaky")).isEmpty();

        faults.update(new ErpApi.FaultSettings(0.0, 0)); // ERP recovers
        worker.runOnce();

        assertThat(jobs.findById(1).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(jobs.findById(1).orElseThrow().attempts()).isEqualTo(2);
        assertThat(erpRecords.findByRef("pi_flaky")).isPresent();
    }

    @Test
    void timeoutAfterErpWroteIsRetriedWithoutCreatingADuplicate() throws Exception {
        webhook(paymentSucceeded("evt_pay", "pi_slow", 1000));
        faults.update(new ErpApi.FaultSettings(0.0, 1_000)); // ERP writes, then answers after our 500ms timeout

        worker.runOnce();

        assertThat(jobs.findById(1).orElseThrow().status()).isEqualTo(JobStatus.PENDING); // we saw a timeout...
        assertThat(erpRecords.findByRef("pi_slow")).isPresent();                       // ...but the ERP did the work

        faults.update(new ErpApi.FaultSettings(0.0, 0));
        worker.runOnce();

        assertThat(jobs.findById(1).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(count("erp_records")).isEqualTo(1);
    }

    @Test
    void mockErpInvoiceCreationIsIdempotent() {
        erpClient.createInvoice("pi_twice", 1000, "usd");
        erpClient.createInvoice("pi_twice", 1000, "usd");

        assertThat(count("erp_records")).isEqualTo(1);
    }

    @Test
    void mockErpFaultSettingsCanBeChangedAtRuntime() throws Exception {
        mvc.perform(post("/mock-erp/config").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/mock-erp/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failureRate\":0.25,\"latencyMs\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureRate").value(0.25));
        mvc.perform(put("/mock-erp/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failureRate\":2.0,\"latencyMs\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statsEndpointReportsCounts() throws Exception {
        webhook(paymentSucceeded("evt_pay", "pi_stats", 1000));
        webhook(paymentSucceeded("evt_pay", "pi_stats", 1000)); // duplicate
        worker.runOnce();

        mvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.PAID").value(1))
                .andExpect(jsonPath("$.syncJobs.SUCCEEDED").value(1))
                .andExpect(jsonPath("$.erpRecords").value(1));
    }

    private void webhook(String payload) throws Exception {
        mvc.perform(post("/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", sign(payload))
                        .content(payload))
                .andExpect(status().isOk());
    }
}
