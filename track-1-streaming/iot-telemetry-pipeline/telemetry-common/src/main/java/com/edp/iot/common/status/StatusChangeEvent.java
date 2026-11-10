package com.edp.iot.common.status;

import java.time.Instant;
import java.util.UUID;

/**
 * Published on {@code device.status-changed}. Delivery is at least once (outbox relay), so
 * consumers de-duplicate on {@code changeId}.
 */
public record StatusChangeEvent(
        UUID changeId,
        String deviceId,
        String tenantId,
        DeviceStatus from,
        DeviceStatus to,
        ChangeCause cause,
        long bootId,
        long sequence,
        Instant changedAt) {
}
