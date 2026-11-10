package com.ledgersync.reconciliation;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/reconciliation/runs")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReconciliationService.Report trigger() {
        return service.run();
    }

    @GetMapping("/{id}")
    public ReconciliationService.Report get(@PathVariable long id) {
        return service.report(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run " + id + " not found"));
    }

    @GetMapping
    public List<ReconciliationRun> recent() {
        return service.recentRuns(20);
    }
}
