package com.ledgersync.erp;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/** Request/response bodies of the (mock) ERP's HTTP API, shared by the mock server and our client. */
public final class ErpApi {

    private ErpApi() {
    }

    public record CreateInvoiceRequest(@NotBlank String externalRef, @PositiveOrZero long amountCents,
                                       @NotBlank String currency) {
    }

    /** Absolute refunded total, not a delta, so replaying the same request is harmless. */
    public record RecordRefundRequest(@PositiveOrZero long refundedCents) {
    }

    public record FaultSettings(@DecimalMin("0.0") @DecimalMax("1.0") double failureRate,
                                @PositiveOrZero long latencyMs) {
    }
}
