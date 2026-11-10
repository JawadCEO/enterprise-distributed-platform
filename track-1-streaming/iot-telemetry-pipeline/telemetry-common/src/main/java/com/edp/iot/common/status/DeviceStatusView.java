package com.edp.iot.common.status;

import java.time.Instant;
import java.util.Map;

/** Current status as cached in Redis ({@code st:<deviceId>}) and returned by the status API. */
public record DeviceStatusView(
        String deviceId,
        String tenantId,
        DeviceStatus status,
        long bootId,
        long lastSequence,
        Instant lastSeen,
        Instant statusSince,
        Map<String, Object> metrics) {

    public static final String CACHE_PREFIX = "st:";

    public static DeviceStatusView of(StatusState state) {
        return new DeviceStatusView(state.deviceId(), state.tenantId(), state.status(), state.key().bootId(),
                state.key().sequence(), state.lastSeen(), state.statusSince(), state.metrics());
    }

    public static String cacheKey(String deviceId) {
        return CACHE_PREFIX + deviceId;
    }
}
