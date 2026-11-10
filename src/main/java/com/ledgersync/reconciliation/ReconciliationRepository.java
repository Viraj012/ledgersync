package com.ledgersync.reconciliation;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationRepository {

    private final JdbcClient jdbc;

    public ReconciliationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long startRun() {
        return jdbc.sql("INSERT INTO reconciliation_runs DEFAULT VALUES RETURNING id").query(Long.class).single();
    }

    /** clock_timestamp(), not now(): now() is frozen at transaction start. */
    public void finishRun(long runId, int ordersChecked, int issuesFound) {
        jdbc.sql("""
                        UPDATE reconciliation_runs
                           SET finished_at = clock_timestamp(), orders_checked = :checked, issues_found = :issues
                         WHERE id = :id
                        """)
                .param("checked", ordersChecked)
                .param("issues", issuesFound)
                .param("id", runId)
                .update();
    }

    public void insertIssue(long runId, ReconciliationRules.Finding finding) {
        jdbc.sql("""
                        INSERT INTO reconciliation_issues (run_id, order_id, external_ref, issue_type, details)
                        VALUES (:runId, :orderId, :ref, :type, :details)
                        """)
                .param("runId", runId)
                .param("orderId", finding.orderId())
                .param("ref", finding.externalRef())
                .param("type", finding.type().name())
                .param("details", finding.details())
                .update();
    }

    public Optional<ReconciliationRun> findRun(long id) {
        return jdbc.sql("SELECT * FROM reconciliation_runs WHERE id = :id")
                .param("id", id)
                .query(ReconciliationRun.class)
                .optional();
    }

    public List<ReconciliationRun> findRecentRuns(int limit) {
        return jdbc.sql("SELECT * FROM reconciliation_runs ORDER BY id DESC LIMIT :limit")
                .param("limit", limit)
                .query(ReconciliationRun.class)
                .list();
    }

    public List<ReconciliationIssue> findIssues(long runId) {
        return jdbc.sql("SELECT * FROM reconciliation_issues WHERE run_id = :runId ORDER BY id")
                .param("runId", runId)
                .query(ReconciliationIssue.class)
                .list();
    }
}
