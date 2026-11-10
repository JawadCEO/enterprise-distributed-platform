package com.edp.iot.common.line;

import java.time.Duration;

/** Registry facts the pipeline needs about one line, joined from line, device_kind and customer. */
public record LineInfo(
        String iccid,
        String customerId,
        SimType simType,
        String deviceKind,
        LineState lineState,
        int keepAliveSeconds,
        long dataAllowanceBytes,
        String homeRegion,
        int customerRateLimitPerSec,
        boolean autoSuspendOnSimMove) {

    public Duration keepAliveWindow() {
        return Duration.ofSeconds(keepAliveSeconds);
    }

    public boolean isDeactivated() {
        return lineState == LineState.DEACTIVATED;
    }
}
