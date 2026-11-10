package com.edp.iot.gateway.ratelimit;

import com.edp.iot.common.registry.DeviceInfo;

public interface RateLimiter {

    /**
     * Takes up to {@code requested} events from the device's and its tenant's allowance for the
     * current one-second window.
     *
     * @return how many of the requested events may pass; the rest are rate limited
     */
    int acquire(DeviceInfo device, int requested);

    /** Milliseconds until the current window ends, sent back as {@code Retry-After}. */
    default long millisUntilNextWindow() {
        return 1000 - System.currentTimeMillis() % 1000;
    }
}
