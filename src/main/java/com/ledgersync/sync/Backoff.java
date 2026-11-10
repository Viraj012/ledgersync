package com.ledgersync.sync;

import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with a cap and jitter: {@code base * 2^(failures-1)}, plus up to 10% random extra,
 * never more than {@code cap}. With base 5s: 5s, 10s, 20s, 40s... up to 10 min.
 *
 * <p>Jitter spreads retries out so a burst of jobs that failed together (ERP outage) doesn't hammer the
 * ERP again all in the same second when it comes back.
 */
public final class Backoff {

    private static final double JITTER_FRACTION = 0.10;

    private final long baseMs;
    private final long capMs;
    private final DoubleSupplier random;

    /** @param random returns values in [0, 1); injectable so tests are deterministic */
    public Backoff(long baseMs, long capMs, DoubleSupplier random) {
        this.baseMs = baseMs;
        this.capMs = capMs;
        this.random = random;
    }

    /** @param failures how many attempts have failed so far (1 after the first failure) */
    public long delayMs(int failures) {
        long exponential = exponentialMs(failures);
        long jitter = (long) (exponential * JITTER_FRACTION * random.getAsDouble());
        return Math.min(capMs, exponential + jitter);
    }

    long exponentialMs(int failures) {
        int doublings = Math.max(0, failures - 1);
        // base * 2^d > cap  <=>  base > cap / 2^d. Checking it this way can't overflow a long.
        if (doublings >= 62 || baseMs > (capMs >> doublings)) {
            return capMs;
        }
        return baseMs << doublings;
    }
}
