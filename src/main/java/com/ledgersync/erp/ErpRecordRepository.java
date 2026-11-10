package com.ledgersync.erp;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ErpRecordRepository {

    private final JdbcClient jdbc;

    public ErpRecordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** UNIQUE(external_ref) makes invoice creation idempotent: a retried request can't create a second invoice. */
    public boolean insertIfAbsent(String externalRef, long amountCents, String currency) {
        return jdbc.sql("""
                        INSERT INTO erp_records (external_ref, amount_cents, currency)
                        VALUES (:ref, :amount, :currency)
                        ON CONFLICT (external_ref) DO NOTHING
                        """)
                .param("ref", externalRef)
                .param("amount", amountCents)
                .param("currency", currency)
                .update() == 1;
    }

    public boolean setRefunded(String externalRef, long refundedCents) {
        return jdbc.sql("""
                        UPDATE erp_records SET refunded_cents = :refunded, updated_at = now()
                         WHERE external_ref = :ref
                        """)
                .param("refunded", refundedCents)
                .param("ref", externalRef)
                .update() == 1;
    }

    public Optional<ErpRecord> findByRef(String externalRef) {
        return jdbc.sql("SELECT * FROM erp_records WHERE external_ref = :ref")
                .param("ref", externalRef)
                .query(ErpRecord.class)
                .optional();
    }

    public List<ErpRecord> findAll() {
        return jdbc.sql("SELECT * FROM erp_records ORDER BY id").query(ErpRecord.class).list();
    }
}
