package com.edp.iot.common.registry;

import com.edp.iot.common.status.Thresholds;

import java.time.Duration;

/** Registry facts the pipeline needs about one device, joined from device, device_type and tenant. */
public record DeviceInfo(
        String deviceId,
        String tenantId,
        String deviceType,
        boolean active,
        String credentialHash,
        int heartbeatSeconds,
        String homeRegion,
        int tenantRateLimitPerSec,
        Thresholds thresholds) {

    public Duration heartbeatWindow() {
        return Duration.ofSeconds(heartbeatSeconds);
    }
}
