package com.ledgersync.sync;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SyncJobController {

    private final SyncJobRepository jobs;

    public SyncJobController(SyncJobRepository jobs) {
        this.jobs = jobs;
    }

    /** {@code GET /sync-jobs?status=FAILED} is the failure queue. */
    @GetMapping("/sync-jobs")
    public List<SyncJob> list(@RequestParam(required = false) JobStatus status,
                              @RequestParam(defaultValue = "100") int limit) {
        return jobs.findRecent(status, Math.min(limit, 1000));
    }

    @PostMapping("/sync-jobs/{id}/retry")
    public SyncJob retry(@PathVariable long id) {
        SyncJob job = jobs.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sync job " + id + " not found"));
        if (!jobs.requeueFailed(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only FAILED jobs can be retried; job " + id + " is " + job.status());
        }
        return jobs.findById(id).orElseThrow();
    }
}
