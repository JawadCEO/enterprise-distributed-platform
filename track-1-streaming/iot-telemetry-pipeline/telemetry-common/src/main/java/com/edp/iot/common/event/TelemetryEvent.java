package com.edp.iot.common.event;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An event as it travels through Kafka: the device's payload plus the fields the gateway adds
 * ({@code tenantId}, {@code receivedAt}).
 */
public record TelemetryEvent(
        UUID eventId,
        String deviceId,
        String tenantId,
        long bootId,
        long sequence,
        Instant deviceTime,
        Instant receivedAt,
        EventType type,
        Map<String, Object> metrics) {

    public static final String FAULT_CODE = "faultCode";

    public TelemetryEvent {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    @JsonIgnore
    public OrderingKey key() {
        return new OrderingKey(bootId, sequence);
    }

    /** Key used for duplicate detection (ADR-006). */
    @JsonIgnore
    public String dedupId() {
        return deviceId + ":" + bootId + ":" + sequence;
    }
}
