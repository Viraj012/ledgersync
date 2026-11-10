package com.ledgersync.erp;

import com.ledgersync.config.ErpProperties;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Makes the mock ERP behave like a flaky third-party API. Adjustable at runtime via PUT /mock-erp/config. */
@Component
public class MockErpFaults {

    private volatile double failureRate;
    private volatile long latencyMs;

    public MockErpFaults(ErpProperties props) {
        this.failureRate = props.mock().failureRate();
        this.latencyMs = props.mock().latencyMs();
    }

    /** Fails before doing any work, like a 503 from an overloaded server. */
    void maybeFail() {
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated ERP outage");
        }
    }

    /**
     * Slow response <em>after</em> the write is committed. If this exceeds the client's timeout, the client
     * sees a failure although the ERP did the work, which is exactly the case idempotent writes protect against.
     */
    void simulateLatency() {
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public ErpApi.FaultSettings settings() {
        return new ErpApi.FaultSettings(failureRate, latencyMs);
    }

    public void update(ErpApi.FaultSettings settings) {
        this.failureRate = settings.failureRate();
        this.latencyMs = settings.latencyMs();
    }
}
