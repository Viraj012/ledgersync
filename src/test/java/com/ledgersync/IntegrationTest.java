package com.ledgersync;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests: a real Postgres 16 (one container shared by all test classes),
 * Flyway-migrated, wiped before each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("""
                TRUNCATE reconciliation_issues, reconciliation_runs, sync_jobs, orders, erp_records, stripe_events
                RESTART IDENTITY CASCADE
                """).update();
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
