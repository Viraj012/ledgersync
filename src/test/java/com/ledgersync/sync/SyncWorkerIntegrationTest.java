package com.ledgersync.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgersync.IntegrationTest;
import com.ledgersync.order.OrderRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** ERP client is mocked so we can script exactly when it fails. Max attempts is 5 (application.yml). */
class SyncWorkerIntegrationTest extends IntegrationTest {

    @MockitoBean
    ErpClient erp;

    @Autowired
    SyncWorker worker;

    @Autowired
    SyncJobRepository jobs;

    @Autowired
    OrderRepository orders;

    @Autowired
    MockMvc mvc;

    @Test
    void erpFailsThreeTimesThenSucceeds() {
        long jobId = queueInvoice("pi_1", 5000);
        doThrow(new RuntimeException("ERP down"))
                .doThrow(new RuntimeException("ERP down"))
                .doThrow(new RuntimeException("ERP down"))
                .doNothing()
                .when(erp).createInvoice("pi_1", 5000, "usd");

        drain();

        SyncJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.attempts()).isEqualTo(4); // N failures + 1 success
        assertThat(job.lastError()).isNull();
    }

    @Test
    void erpAlwaysFailingEndsInFailureQueueAfterMaxAttempts() {
        long jobId = queueInvoice("pi_1", 5000);
        doThrow(new RuntimeException("ERP down")).when(erp).createInvoice(any(), anyLong(), any());

        drain();

        SyncJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.attempts()).isEqualTo(5);
        assertThat(job.lastError()).contains("ERP down");
    }

    @Test
    void failedAttemptIsScheduledInTheFutureWhenBackoffIsNonZero() {
        long jobId = queueInvoice("pi_1", 5000);
        jobs.claimDue(1);

        jobs.markRetry(jobId, "boom", 60_000);

        assertThat(jobs.claimDue(10)).isEmpty(); // not due yet
        SyncJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.PENDING);
        assertThat(job.nextAttemptAt()).isAfter(job.updatedAt().plusSeconds(59));
    }

    @Test
    void refundJobSendsTheOrdersCurrentRefundedTotal() {
        long orderId = orders.insertIfAbsent("pi_1", null, 5000, "usd").orElseThrow();
        orders.updateRefund(orderId, 1500, com.ledgersync.order.OrderStatus.PARTIALLY_REFUNDED);
        jobs.insert(orderId, JobType.RECORD_REFUND);

        drain();

        verify(erp).recordRefund("pi_1", 1500);
        verify(erp, never()).createInvoice(any(), anyLong(), any());
    }

    @Test
    void refundSyncWaitsUntilItsInvoiceIsInTheErp() {
        long orderId = orders.insertIfAbsent("pi_1", null, 5000, "usd").orElseThrow();
        long invoiceJob = jobs.insert(orderId, JobType.CREATE_INVOICE);
        orders.updateRefund(orderId, 1000, com.ledgersync.order.OrderStatus.PARTIALLY_REFUNDED);
        long refundJob = jobs.insert(orderId, JobType.RECORD_REFUND);
        doThrow(new RuntimeException("ERP down")).doNothing().when(erp).createInvoice(any(), anyLong(), any());

        worker.runOnce(); // only the invoice is claimable, and it fails

        verify(erp, never()).recordRefund(any(), anyLong());
        assertThat(jobs.findById(refundJob).orElseThrow().attempts()).isZero(); // no attempt wasted

        drain();

        InOrder order = inOrder(erp);
        order.verify(erp, times(2)).createInvoice("pi_1", 5000, "usd");
        order.verify(erp).recordRefund("pi_1", 1000);
        assertThat(jobs.findById(invoiceJob).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        SyncJob refund = jobs.findById(refundJob).orElseThrow();
        assertThat(refund.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(refund.attempts()).isEqualTo(1);
    }

    @Test
    void refundSyncIsReleasedIfItsInvoiceGaveUp() {
        long orderId = orders.insertIfAbsent("pi_1", null, 5000, "usd").orElseThrow();
        long invoiceJob = jobs.insert(orderId, JobType.CREATE_INVOICE);
        long refundJob = jobs.insert(orderId, JobType.RECORD_REFUND);
        jdbc.sql("UPDATE sync_jobs SET status = 'FAILED' WHERE id = :id").param("id", invoiceJob).update();

        assertThat(jobs.claimDue(10)).extracting(SyncJob::id).containsExactly(refundJob);
    }

    @Test
    void twoWorkersRunningConcurrentlyNeverProcessTheSameJob() throws Exception {
        int jobCount = 40;
        for (int i = 0; i < jobCount; i++) {
            queueInvoice("pi_" + i, 100 + i);
        }
        Map<String, Integer> callsPerRef = new ConcurrentHashMap<>();
        doAnswer(inv -> {
            callsPerRef.merge(inv.getArgument(0), 1, Integer::sum);
            Thread.sleep(10); // widen the window for a race
            return null;
        }).when(erp).createInvoice(any(), anyLong(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> workers = new ArrayList<>();
        for (int w = 0; w < 2; w++) {
            workers.add(pool.submit(() -> {
                start.await();
                int processed = 0;
                int claimed;
                while ((claimed = worker.runOnce()) > 0) {
                    processed += claimed;
                }
                return processed;
            }));
        }
        start.countDown();
        int total = 0;
        List<Integer> perWorker = new ArrayList<>();
        for (Future<Integer> f : workers) {
            int n = f.get(30, TimeUnit.SECONDS);
            perWorker.add(n);
            total += n;
        }
        pool.shutdown();

        assertThat(total).isEqualTo(jobCount);
        assertThat(callsPerRef).hasSize(jobCount);
        assertThat(callsPerRef.values()).allMatch(calls -> calls == 1);
        assertThat(perWorker).allMatch(n -> n > 0); // both workers actually shared the load
        assertThat(jdbc.sql("SELECT count(*) FROM sync_jobs WHERE status = 'SUCCEEDED'").query(Long.class).single())
                .isEqualTo(jobCount);
    }

    @Test
    void jobsStuckInProgressAreRecovered() {
        long jobId = queueInvoice("pi_1", 5000);
        jdbc.sql("UPDATE sync_jobs SET status = 'IN_PROGRESS', updated_at = now() - interval '1 hour' WHERE id = :id")
                .param("id", jobId).update();

        assertThat(worker.recoverStuckJobs()).isEqualTo(1);

        SyncJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.PENDING);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.lastError()).contains("Recovered");
    }

    @Test
    void recentlyClaimedJobsAreNotTreatedAsStuck() {
        queueInvoice("pi_1", 5000);
        jobs.claimDue(1);

        assertThat(worker.recoverStuckJobs()).isZero();
    }

    @Test
    void failureQueueCanBeListedAndRetried() throws Exception {
        long jobId = queueInvoice("pi_1", 5000);
        jdbc.sql("UPDATE sync_jobs SET status = 'FAILED', attempts = 5, last_error = 'ERP down' WHERE id = :id")
                .param("id", jobId).update();

        mvc.perform(get("/sync-jobs").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(jobId))
                .andExpect(jsonPath("$[0].lastError").value("ERP down"));

        mvc.perform(post("/sync-jobs/{id}/retry", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));

        mvc.perform(post("/sync-jobs/{id}/retry", jobId)).andExpect(status().isConflict());
        mvc.perform(post("/sync-jobs/{id}/retry", 999)).andExpect(status().isNotFound());

        drain();
        assertThat(jobs.findById(jobId).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void orderEndpointShowsSyncStatus() throws Exception {
        queueInvoice("pi_1", 5000);
        drain();

        mvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.paymentIntentId").value("pi_1"))
                .andExpect(jsonPath("$.syncJobs[0].status").value("SUCCEEDED"));
        mvc.perform(get("/orders/999")).andExpect(status().isNotFound());
    }

    private long queueInvoice(String paymentIntentId, long amountCents) {
        long orderId = orders.insertIfAbsent(paymentIntentId, null, amountCents, "usd").orElseThrow();
        return jobs.insert(orderId, JobType.CREATE_INVOICE);
    }

    /** Backoff is 0 in tests, so retries are due immediately; keep polling until nothing is left. */
    private void drain() {
        for (int i = 0; i < 50 && worker.runOnce() > 0; i++) {
            // keep going
        }
    }
}
