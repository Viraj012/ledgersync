package com.ledgersync.order;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a PAID order unless one already exists for this PaymentIntent.
     * The UNIQUE constraint on payment_intent_id is the second line of defence against duplicates
     * (the first is stripe_events.event_id).
     *
     * @return the new order's id, or empty if the order already existed
     */
    public Optional<Long> insertIfAbsent(String paymentIntentId, String customerEmail, long amountCents, String currency) {
        return jdbc.sql("""
                        INSERT INTO orders (payment_intent_id, customer_email, amount_cents, currency, status)
                        VALUES (:pi, :email, :amount, :currency, 'PAID')
                        ON CONFLICT (payment_intent_id) DO NOTHING
                        RETURNING id
                        """)
                .param("pi", paymentIntentId)
                .param("email", customerEmail)
                .param("amount", amountCents)
                .param("currency", currency)
                .query(Long.class)
                .optional();
    }

    public Optional<Order> findById(long id) {
        return jdbc.sql("SELECT * FROM orders WHERE id = :id")
                .param("id", id)
                .query(Order.class)
                .optional();
    }

    /** Row-locks the order so two refund events for the same payment can't interleave. */
    public Optional<Order> findByPaymentIntentIdForUpdate(String paymentIntentId) {
        return jdbc.sql("SELECT * FROM orders WHERE payment_intent_id = :pi FOR UPDATE")
                .param("pi", paymentIntentId)
                .query(Order.class)
                .optional();
    }

    public void updateRefund(long id, long refundedCents, OrderStatus status) {
        jdbc.sql("""
                        UPDATE orders
                           SET refunded_cents = :refunded, status = :status, updated_at = now()
                         WHERE id = :id
                        """)
                .param("refunded", refundedCents)
                .param("status", status.name())
                .param("id", id)
                .update();
    }

    public List<Order> findAll() {
        return jdbc.sql("SELECT * FROM orders ORDER BY id").query(Order.class).list();
    }
}
