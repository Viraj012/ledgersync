package com.ledgersync.webhook;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StripeEventRepository {

    private final JdbcClient jdbc;

    public StripeEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event id, the idempotency key.
     *
     * <p>{@code ON CONFLICT DO NOTHING} instead of catching a unique-violation: in Postgres a failed
     * statement aborts the whole transaction, while this just reports "0 rows". If two deliveries of the
     * same event race, the second INSERT waits on the first's row lock, then sees the committed row and
     * inserts nothing.
     *
     * @return true the first time we see this event, false for a duplicate delivery
     */
    public boolean insertIfAbsent(String eventId, String eventType, String status) {
        return jdbc.sql("""
                        INSERT INTO stripe_events (event_id, event_type, status)
                        VALUES (:eventId, :eventType, :status)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .param("status", status)
                .update() == 1;
    }
}
