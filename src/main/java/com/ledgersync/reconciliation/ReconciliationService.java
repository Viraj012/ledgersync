package com.ledgersync.reconciliation;

import com.ledgersync.erp.ErpRecord;
import com.ledgersync.erp.ErpRecordRepository;
import com.ledgersync.order.Order;
import com.ledgersync.order.OrderRepository;
import com.ledgersync.sync.SyncJobRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {

    public record Report(ReconciliationRun run, List<ReconciliationIssue> issues) {
    }

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final OrderRepository orders;
    private final ErpRecordRepository erpRecords;
    private final SyncJobRepository syncJobs;
    private final ReconciliationRepository runs;

    public ReconciliationService(OrderRepository orders, ErpRecordRepository erpRecords,
                                 SyncJobRepository syncJobs, ReconciliationRepository runs) {
        this.orders = orders;
        this.erpRecords = erpRecords;
        this.syncJobs = syncJobs;
        this.runs = runs;
    }

    /**
     * REPEATABLE READ gives every query here the same snapshot. Under the default READ COMMITTED, a sync job
     * finishing between "read orders" and "read ERP records" could produce a false MISSING_IN_ERP.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Report run() {
        long runId = runs.startRun();

        List<Order> allOrders = orders.findAll();
        List<ErpRecord> allErp = erpRecords.findAll();
        Set<Long> openJobs = syncJobs.orderIdsWithOpenJobs();

        List<ReconciliationRules.Finding> findings = ReconciliationRules.compare(allOrders, allErp, openJobs);
        findings.forEach(f -> runs.insertIssue(runId, f));
        runs.finishRun(runId, allOrders.size(), findings.size());

        log.info("Reconciliation run {}: {} orders checked, {} issue(s)", runId, allOrders.size(), findings.size());
        return report(runId).orElseThrow();
    }

    public Optional<Report> report(long runId) {
        return runs.findRun(runId).map(run -> new Report(run, runs.findIssues(runId)));
    }

    public List<ReconciliationRun> recentRuns(int limit) {
        return runs.findRecentRuns(limit);
    }
}
