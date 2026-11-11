package com.edp.iot.notifier;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Exponential back-off with jitter: initial, then doubling up to a cap, each spread by +/-20%. */
final class Backoff {

    private final Duration initial;
    private final Duration max;

    Backoff(Duration initial, Duration max) {
        this.initial = initial;
        this.max = max;
    }

    /** Delay before attempt {@code failures + 1}, after {@code failures} failed attempts (at least 1). */
    Duration after(int failures) {
        long base = initial.toMillis() << Math.min(30, Math.max(0, failures - 1));
        long capped = Math.min(max.toMillis(), base <= 0 ? max.toMillis() : base);
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Duration.ofMillis(Math.max(1, (long) (capped * jitter)));
    }
}
