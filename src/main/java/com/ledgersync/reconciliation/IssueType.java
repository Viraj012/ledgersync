package com.ledgersync.reconciliation;

public enum IssueType {
    /** We have the order; the ERP has no invoice, and no sync is still in flight. */
    MISSING_IN_ERP,
    /** Both sides have it but the amount or currency differs. */
    AMOUNT_MISMATCH,
    /** Refunded totals differ and no refund sync is still in flight. */
    REFUND_NOT_SYNCED,
    /** The ERP has an invoice we have no order for. */
    ORPHAN_IN_ERP
}
