package com.ledgersync.erp;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stand-in for a real ERP (NetSuite, QuickBooks...). It lives in this app only for convenience: the sync
 * worker talks to it over HTTP with a timeout, exactly as it would to a third-party API.
 */
@RestController
@RequestMapping("/mock-erp")
public class MockErpController {

    private final ErpRecordRepository records;
    private final MockErpFaults faults;

    public MockErpController(ErpRecordRepository records, MockErpFaults faults) {
        this.records = records;
        this.faults = faults;
    }

    /** 201 when created, 200 when the invoice already existed (idempotent replay). */
    @PostMapping("/invoices")
    public ResponseEntity<ErpRecord> createInvoice(@Valid @RequestBody ErpApi.CreateInvoiceRequest request) {
        faults.maybeFail();
        boolean created = records.insertIfAbsent(request.externalRef(), request.amountCents(), request.currency());
        ErpRecord record = records.findByRef(request.externalRef()).orElseThrow();
        faults.simulateLatency();
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(record);
    }

    @PostMapping("/invoices/{ref}/refunds")
    public ErpRecord recordRefund(@PathVariable String ref, @Valid @RequestBody ErpApi.RecordRefundRequest request) {
        faults.maybeFail();
        if (!records.setRefunded(ref, request.refundedCents())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No invoice " + ref);
        }
        ErpRecord record = records.findByRef(ref).orElseThrow();
        faults.simulateLatency();
        return record;
    }

    @GetMapping("/invoices/{ref}")
    public ErpRecord getInvoice(@PathVariable String ref) {
        return records.findByRef(ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No invoice " + ref));
    }

    @GetMapping("/config")
    public ErpApi.FaultSettings getConfig() {
        return faults.settings();
    }

    /** Turn the chaos up or down at runtime, e.g. {"failureRate": 0.3, "latencyMs": 0}. */
    @PutMapping("/config")
    public ErpApi.FaultSettings setConfig(@Valid @RequestBody ErpApi.FaultSettings settings) {
        faults.update(settings);
        return faults.settings();
    }
}
