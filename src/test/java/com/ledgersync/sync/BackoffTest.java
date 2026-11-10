package com.ledgersync.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BackoffTest {

    private static final long BASE = 5_000;
    private static final long CAP = 600_000;

    @Test
    void doublesWithEachFailure() {
        Backoff backoff = new Backoff(BASE, CAP, () -> 0.0);

        assertThat(backoff.delayMs(1)).isEqualTo(5_000);
        assertThat(backoff.delayMs(2)).isEqualTo(10_000);
        assertThat(backoff.delayMs(3)).isEqualTo(20_000);
        assertThat(backoff.delayMs(4)).isEqualTo(40_000);
    }

    @Test
    void neverExceedsTheCap() {
        Backoff backoff = new Backoff(BASE, CAP, () -> 0.99);

        assertThat(backoff.delayMs(8)).isEqualTo(CAP);   // 5s * 2^7 = 640s > 600s
        assertThat(backoff.delayMs(50)).isEqualTo(CAP);
        assertThat(backoff.delayMs(1_000)).isEqualTo(CAP); // no overflow
    }

    @Test
    void jitterAddsUpToTenPercent() {
        assertThat(new Backoff(BASE, CAP, () -> 0.5).delayMs(2)).isEqualTo(10_500);
        assertThat(new Backoff(BASE, CAP, () -> 0.999).delayMs(2)).isBetween(10_000L, 11_000L);
    }

    @Test
    void zeroBaseMeansImmediateRetry() {
        assertThat(new Backoff(0, CAP, () -> 0.5).delayMs(3)).isZero();
    }
}
