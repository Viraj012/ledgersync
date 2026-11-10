package com.ledgersync.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgersync.IntegrationTest;
import com.ledgersync.erp.ErpRecordRepository;
import com.ledgersync.order.OrderRepository;
import com.ledgersync.order.OrderStatus;
import com.ledgersync.sync.JobType;
import com.ledgersync.sync.SyncJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class ReconciliationIntegrationTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OrderRepository orders;

    @Autowired
    ErpRecordRepository erp;

    @Autowired
    SyncJobRepository jobs;

    @Autowired
    ReconciliationService service;

    @Test
    void detectsEveryIssueTypeAndServesTheReport() throws Exception {
        // In sync: no issue
        orders.insertIfAbsent("pi_ok", null, 1000, "usd");
        erp.insertIfAbsent("pi_ok", 1000, "usd");
        // MISSING_IN_ERP: sync job gave up
        long missing = orders.insertIfAbsent("pi_missing", null, 2000, "usd").orElseThrow();
        failJob(jobs.insert(missing, JobType.CREATE_INVOICE));
        // Still syncing: must NOT be flagged
        long inFlight = orders.insertIfAbsent("pi_inflight", null, 2500, "usd").orElseThrow();
        jobs.insert(inFlight, JobType.CREATE_INVOICE);
        // AMOUNT_MISMATCH
        orders.insertIfAbsent("pi_amount", null, 3000, "usd");
        erp.insertIfAbsent("pi_amount", 2999, "usd");
        // REFUND_NOT_SYNCED
        long refunded = orders.insertIfAbsent("pi_refund", null, 4000, "usd").orElseThrow();
        orders.updateRefund(refunded, 4000, OrderStatus.REFUNDED);
        erp.insertIfAbsent("pi_refund", 4000, "usd");
        // ORPHAN_IN_ERP
        erp.insertIfAbsent("pi_orphan", 500, "usd");

        String body = mvc.perform(post("/reconciliation/runs"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.run.ordersChecked").value(5))
                .andExpect(jsonPath("$.run.issuesFound").value(4))
                .andExpect(jsonPath("$.run.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$.issues[*].issueType").value(containsInAnyOrder(
                        "MISSING_IN_ERP", "AMOUNT_MISMATCH", "REFUND_NOT_SYNCED", "ORPHAN_IN_ERP")))
                .andExpect(jsonPath("$.issues[*].externalRef").value(containsInAnyOrder(
                        "pi_missing", "pi_amount", "pi_refund", "pi_orphan")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("pi_ok").doesNotContain("pi_inflight");

        mvc.perform(get("/reconciliation/runs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues.length()").value(4));
        mvc.perform(get("/reconciliation/runs")).andExpect(jsonPath("$[0].id").value(1));
        mvc.perform(get("/reconciliation/runs/999")).andExpect(status().isNotFound());
    }

    @Test
    void cleanDataGivesCleanRun() {
        orders.insertIfAbsent("pi_1", null, 1000, "usd");
        erp.insertIfAbsent("pi_1", 1000, "usd");

        ReconciliationService.Report report = service.run();

        assertThat(report.run().ordersChecked()).isEqualTo(1);
        assertThat(report.run().issuesFound()).isZero();
        assertThat(report.issues()).isEmpty();
    }

    private void failJob(long jobId) {
        jdbc.sql("UPDATE sync_jobs SET status = 'FAILED', attempts = 5 WHERE id = :id").param("id", jobId).update();
    }
}
