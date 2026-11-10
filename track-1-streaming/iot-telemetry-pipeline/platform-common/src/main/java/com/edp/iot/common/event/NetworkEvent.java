package com.edp.iot.common.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One event as it travels through Kafka (data.md section 2.1): the sender's envelope plus the
 * fields event-ingest adds ({@code customerId}, {@code receivedAt}). Device events carry
 * {@code bootId} and {@code sequence}; network events carry {@code networkTime} instead.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NetworkEvent(
        UUID eventId,
        EventType type,
        String lineId,
        String imei,
        String customerId,
        Long bootId,
        Long sequence,
        Instant deviceTime,
        Instant networkTime,
        Instant receivedAt,
        Map<String, Object> payload) {

    /** Payload fields used by the processor. */
    public static final String BATTERY_PCT = "batteryPct";
    public static final String SIGNAL_DBM = "signalDbm";
    public static final String TEMPERATURE_C = "temperatureC";
    public static final String FIRMWARE = "firmware";
    public static final String FAULT_CODE = "faultCode";
    public static final String BYTES_UP = "bytesUp";
    public static final String BYTES_DOWN = "bytesDown";
    public static final String REGION = "region";

    public NetworkEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    @JsonIgnore
    public OrderingKey deviceKey() {
        return bootId == null || sequence == null ? null : new OrderingKey(bootId, sequence);
    }

    @JsonIgnore
    public boolean isDeviceEvent() {
        return type.isDeviceEvent();
    }

    /** Bytes carried by a {@code USAGE_RECORD}, up plus down. */
    @JsonIgnore
    public long usageBytes() {
        return number(payload.get(BYTES_UP)) + number(payload.get(BYTES_DOWN));
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }
}
