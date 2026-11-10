package com.edp.iot.common.state;

import com.edp.iot.common.line.Connection;

import java.time.Instant;
import java.util.Map;

/**
 * Device state as cached in Redis ({@code ds:<iccid>}) and returned inside a line's current state
 * by customer-api (FR-26). Written by state-processor and presence-monitor after each commit.
 */
public record DeviceStateView(
        String iccid,
        String customerId,
        Connection connection,
        String connectionReason,
        Instant connectionSince,
        Instant lastSeen,
        String imei,
        String region,
        Long bootId,
        Long sequence,
        Map<String, Object> metrics) {

    public static final String CACHE_PREFIX = "ds:";

    public static DeviceStateView of(DeviceState s) {
        return new DeviceStateView(s.iccid(), s.customerId(), s.connection(), s.connectionReason(), s.connectionSince(),
                s.lastSeen(), s.imei(), s.region(), s.deviceKey() == null ? null : s.deviceKey().bootId(),
                s.deviceKey() == null ? null : s.deviceKey().sequence(), s.metrics());
    }

    public static String cacheKey(String iccid) {
        return CACHE_PREFIX + iccid;
    }
}
