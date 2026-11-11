package com.edp.iot.provisioning;

import org.springframework.stereotype.Component;

/**
 * Keeps calls to the network at or below the configured rate (FR-9): a token bucket refilled
 * continuously, one second's worth deep, so a burst of commands is spread out instead of sent at once.
 */
@Component
class Pacer {

    private final double ratePerMs;
    private final double capacity;
    private double tokens;
    private long last = System.nanoTime();

    Pacer(ProvisioningProperties props) {
        this.capacity = Math.max(1, props.networkRatePerSec());
        this.ratePerMs = capacity / 1000.0;
        this.tokens = capacity;
    }

    /** Milliseconds to wait before the next call may go out; 0 means go now (a token was taken). */
    synchronized long tryAcquire() {
        long now = System.nanoTime();
        tokens = Math.min(capacity, tokens + (now - last) / 1_000_000.0 * ratePerMs);
        last = now;
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return (long) Math.ceil((1 - tokens) / ratePerMs);
    }
}
