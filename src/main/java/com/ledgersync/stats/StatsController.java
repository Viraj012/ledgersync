package com.ledgersync.stats;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** One-glance numbers for demos and the load simulator. Counters are since app start; the rest is from the DB. */
@RestController
public class StatsController {

    private final JdbcClient jdbc;
    private final MeterRegistry metrics;

    public StatsController(JdbcClient jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("stripeEvents", countBy("stripe_events", "status"));
        stats.put("orders", countBy("orders", "status"));
        stats.put("syncJobs", countBy("sync_jobs", "status"));
        stats.put("syncJobsRetried", count("SELECT count(*) FROM sync_jobs WHERE attempts > 1"));
        stats.put("erpRecords", count("SELECT count(*) FROM erp_records"));
        stats.put("webhookDeliveriesSinceStart", Map.of(
                "processed", counter("ledgersync.webhook.events", "result", "processed"),
                "ignored", counter("ledgersync.webhook.events", "result", "ignored"),
                "duplicate", counter("ledgersync.webhook.events", "result", "duplicate"),
                "deferred", counter("ledgersync.webhook.events", "result", "deferred"),
                "invalidSignature", counter("ledgersync.webhook.events", "result", "invalid_signature")));
        stats.put("erpAttemptsSinceStart", Map.of(
                "succeeded", counter("ledgersync.sync.attempts", "outcome", "succeeded"),
                "retry", counter("ledgersync.sync.attempts", "outcome", "retry"),
                "failed", counter("ledgersync.sync.attempts", "outcome", "failed")));
        return stats;
    }

    /** Table and column names here are constants from this class, never user input. */
    private Map<String, Long> countBy(String table, String column) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT " + column + " AS k, count(*) AS n FROM " + table + " GROUP BY " + column + " ORDER BY " + column)
                .query((rs, i) -> counts.put(rs.getString("k"), rs.getLong("n")))
                .list();
        return counts;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long counter(String name, String tag, String value) {
        Counter c = metrics.find(name).tag(tag, value).counter();
        return c == null ? 0 : (long) c.count();
    }
}
