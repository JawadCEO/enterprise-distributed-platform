package com.edp.iot.ingest.ratelimit;

import com.edp.iot.common.line.LineInfo;

public interface RateLimiter {

    /**
     * Takes up to {@code requested} events from the line's and its customer's allowance for the
     * current one-second window.
     *
     * @return how many of the requested events may pass; the rest are rate limited
     */
    int acquire(LineInfo line, int requested);

    /** Milliseconds until the current window ends, sent back as {@code Retry-After}. */
    default long millisUntilNextWindow() {
        return 1000 - System.currentTimeMillis() % 1000;
    }
}
