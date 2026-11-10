package com.ledgersync.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgersync.erp.ErpRecord;
import com.ledgersync.order.Order;
import com.ledgersync.order.OrderStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReconciliationRulesTest {

    @Test
    void matchingRecordsProduceNoIssues() {
        var findings = ReconciliationRules.compare(
                List.of(order(1, "pi_1", 5000, "usd", 1000)),
                List.of(erp("pi_1", 5000, "USD", 1000)),
                Set.of());

        assertThat(findings).isEmpty();
    }

    @Test
    void orderWithoutErpRecordIsMissing() {
        var findings = ReconciliationRules.compare(List.of(order(1, "pi_1", 5000, "usd", 0)), List.of(), Set.of());

        assertThat(findings).extracting(ReconciliationRules.Finding::type).containsExactly(IssueType.MISSING_IN_ERP);
        assertThat(findings.get(0).orderId()).isEqualTo(1L);
    }

    @Test
    void orderStillBeingSyncedIsNotMissing() {
        var findings = ReconciliationRules.compare(List.of(order(1, "pi_1", 5000, "usd", 0)), List.of(), Set.of(1L));

        assertThat(findings).isEmpty();
    }

    @Test
    void differentAmountIsAMismatch() {
        var findings = ReconciliationRules.compare(
                List.of(order(1, "pi_1", 5000, "usd", 0)), List.of(erp("pi_1", 4999, "usd", 0)), Set.of());

        assertThat(findings).extracting(ReconciliationRules.Finding::type).containsExactly(IssueType.AMOUNT_MISMATCH);
        assertThat(findings.get(0).details()).contains("5000").contains("4999");
    }

    @Test
    void differentCurrencyIsAMismatch() {
        var findings = ReconciliationRules.compare(
                List.of(order(1, "pi_1", 5000, "usd", 0)), List.of(erp("pi_1", 5000, "eur", 0)), Set.of());

        assertThat(findings).extracting(ReconciliationRules.Finding::type).containsExactly(IssueType.AMOUNT_MISMATCH);
    }

    @Test
    void refundTotalsThatDifferAreNotSynced() {
        var findings = ReconciliationRules.compare(
                List.of(order(1, "pi_1", 5000, "usd", 2000)), List.of(erp("pi_1", 5000, "usd", 0)), Set.of());

        assertThat(findings).extracting(ReconciliationRules.Finding::type).containsExactly(IssueType.REFUND_NOT_SYNCED);
    }

    @Test
    void refundStillBeingSyncedIsNotFlagged() {
        var findings = ReconciliationRules.compare(
                List.of(order(1, "pi_1", 5000, "usd", 2000)), List.of(erp("pi_1", 5000, "usd", 0)), Set.of(1L));

        assertThat(findings).isEmpty();
    }

    @Test
    void erpRecordWithoutOrderIsAnOrphan() {
        var findings = ReconciliationRules.compare(List.of(), List.of(erp("pi_ghost", 700, "usd", 0)), Set.of());

        assertThat(findings).extracting(ReconciliationRules.Finding::type).containsExactly(IssueType.ORPHAN_IN_ERP);
        assertThat(findings.get(0).orderId()).isNull();
        assertThat(findings.get(0).externalRef()).isEqualTo("pi_ghost");
    }

    @Test
    void oneRecordCanHaveBothAmountAndRefundIssues() {
        var findings = ReconciliationRules.compare(
                List.of(order(1, "pi_1", 5000, "usd", 100)), List.of(erp("pi_1", 4000, "usd", 0)), Set.of());

        assertThat(findings).extracting(ReconciliationRules.Finding::type)
                .containsExactly(IssueType.AMOUNT_MISMATCH, IssueType.REFUND_NOT_SYNCED);
    }

    private static Order order(long id, String pi, long amount, String currency, long refunded) {
        return new Order(id, pi, null, amount, currency, refunded, OrderStatus.forRefund(amount, refunded), null, null);
    }

    private static ErpRecord erp(String ref, long amount, String currency, long refunded) {
        return new ErpRecord(0, ref, amount, refunded, currency, null, null);
    }
}
