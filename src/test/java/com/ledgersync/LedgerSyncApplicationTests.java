package com.ledgersync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerSyncApplicationTests extends IntegrationTest {

    @Test
    void flywayCreatesAllTables() {
        List<String> tables = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                .query(String.class)
                .list();

        assertThat(tables).contains(
                "stripe_events", "orders", "sync_jobs",
                "erp_records", "reconciliation_runs", "reconciliation_issues");
    }
}
