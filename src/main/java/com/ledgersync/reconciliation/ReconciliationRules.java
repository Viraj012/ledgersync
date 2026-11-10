package com.ledgersync.reconciliation;

import com.ledgersync.erp.ErpRecord;
import com.ledgersync.order.Order;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The comparison itself: pure function, no database, so every rule is unit-testable. */
public final class ReconciliationRules {

    public record Finding(Long orderId, String externalRef, IssueType type, String details) {
    }

    private ReconciliationRules() {
    }

    /**
     * @param ordersWithOpenJobs orders with a PENDING / IN_PROGRESS sync job. Their ERP side is expected to
     *                           lag behind, so it's not drift (yet); if the job ends up FAILED, the next run flags it.
     */
    public static List<Finding> compare(List<Order> orders, List<ErpRecord> erpRecords, Set<Long> ordersWithOpenJobs) {
        Map<String, ErpRecord> erpByRef = new LinkedHashMap<>();
        erpRecords.forEach(r -> erpByRef.put(r.externalRef(), r));

        List<Finding> findings = new ArrayList<>();
        for (Order order : orders) {
            String ref = order.paymentIntentId();
            ErpRecord erp = erpByRef.remove(ref); // whatever is left at the end is an orphan
            boolean syncInFlight = ordersWithOpenJobs.contains(order.id());

            if (erp == null) {
                if (!syncInFlight) {
                    findings.add(new Finding(order.id(), ref, IssueType.MISSING_IN_ERP,
                            "Order %s %s has no ERP invoice".formatted(order.amountCents(), order.currency())));
                }
                continue;
            }
            if (order.amountCents() != erp.amountCents() || !order.currency().equalsIgnoreCase(erp.currency())) {
                findings.add(new Finding(order.id(), ref, IssueType.AMOUNT_MISMATCH,
                        "Stripe %d %s vs ERP %d %s".formatted(
                                order.amountCents(), order.currency(), erp.amountCents(), erp.currency())));
            }
            if (order.refundedCents() != erp.refundedCents() && !syncInFlight) {
                findings.add(new Finding(order.id(), ref, IssueType.REFUND_NOT_SYNCED,
                        "Refunded: Stripe %d vs ERP %d".formatted(order.refundedCents(), erp.refundedCents())));
            }
        }
        for (ErpRecord orphan : erpByRef.values()) {
            findings.add(new Finding(null, orphan.externalRef(), IssueType.ORPHAN_IN_ERP,
                    "ERP invoice %d %s has no matching order".formatted(orphan.amountCents(), orphan.currency())));
        }
        return findings;
    }
}
