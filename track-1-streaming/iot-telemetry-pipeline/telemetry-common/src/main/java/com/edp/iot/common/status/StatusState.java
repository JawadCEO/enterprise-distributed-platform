package com.edp.iot.common.status;

import com.edp.iot.common.event.OrderingKey;

import java.time.Instant;
import java.util.Map;

/** The stored latest state of one device, as held in {@code device_status}. */
public record StatusState(
        String deviceId,
        String tenantId,
        DeviceStatus status,
        OrderingKey key,
        Instant lastSeen,
        Instant statusSince,
        Map<String, Object> metrics) {

    public StatusState {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    public StatusState withStatus(DeviceStatus newStatus, Instant since) {
        return new StatusState(deviceId, tenantId, newStatus, key, lastSeen, since, metrics);
    }
}
