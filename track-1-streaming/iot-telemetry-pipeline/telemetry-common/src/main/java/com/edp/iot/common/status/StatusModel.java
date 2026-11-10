package com.edp.iot.common.status;

import com.edp.iot.common.event.EventType;
import com.edp.iot.common.event.TelemetryEvent;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Derives a device's status from its previous state and one event (data.md, section 3).
 *
 * <p>Precedence is FAULT, then DEGRADED, then ONLINE. A fault is set by a STATUS event that carries
 * {@code faultCode} and cleared by a STATUS event without one; other event types leave it as it was.
 * OFFLINE is never produced here: only presence-monitor sets it.
 */
public final class StatusModel {

    public static final String BATTERY_PCT = "batteryPct";
    public static final String SIGNAL_DBM = "signalDbm";

    /** Result of applying an event that was newer than the stored state. */
    public record Applied(StatusState state, Optional<StatusChangeEvent> change) {
    }

    /**
     * @return empty when the event is not newer than {@code previous} (stale or duplicate), so it
     *     must not touch the stored status
     */
    public static Optional<Applied> apply(StatusState previous, TelemetryEvent event, Thresholds thresholds) {
        if (previous != null && !event.key().isNewerThan(previous.key())) {
            return Optional.empty();
        }

        Map<String, Object> metrics = new HashMap<>(previous == null ? Map.of() : previous.metrics());
        metrics.putAll(event.metrics());

        boolean fault;
        if (event.type() == EventType.STATUS) {
            fault = hasText(event.metrics().get(TelemetryEvent.FAULT_CODE));
            if (!fault) {
                metrics.remove(TelemetryEvent.FAULT_CODE);
            }
        } else {
            fault = previous != null && previous.status() == DeviceStatus.FAULT;
        }

        DeviceStatus next = fault ? DeviceStatus.FAULT
                : isDegraded(metrics, thresholds) ? DeviceStatus.DEGRADED
                : DeviceStatus.ONLINE;

        DeviceStatus before = previous == null ? null : previous.status();
        Instant receivedAt = event.receivedAt();
        Instant lastSeen = previous == null || receivedAt.isAfter(previous.lastSeen()) ? receivedAt : previous.lastSeen();
        boolean changed = next != before;
        Instant since = changed ? receivedAt : previous.statusSince();

        StatusState state = new StatusState(event.deviceId(), event.tenantId(), next, event.key(), lastSeen, since, metrics);
        Optional<StatusChangeEvent> change = changed
                ? Optional.of(new StatusChangeEvent(UUID.randomUUID(), event.deviceId(), event.tenantId(), before, next,
                        causeOf(previous, before, next, event), event.bootId(), event.sequence(), receivedAt))
                : Optional.empty();
        return Optional.of(new Applied(state, change));
    }

    static ChangeCause causeOf(StatusState previous, DeviceStatus before, DeviceStatus next, TelemetryEvent event) {
        if (before == null) {
            return ChangeCause.EVENT;
        }
        if (next == DeviceStatus.FAULT) {
            return ChangeCause.FAULT_REPORTED;
        }
        if (before == DeviceStatus.FAULT) {
            return ChangeCause.FAULT_CLEARED;
        }
        if (before == DeviceStatus.OFFLINE) {
            return event.bootId() > previous.key().bootId() ? ChangeCause.REBOOT : ChangeCause.EVENT;
        }
        return ChangeCause.THRESHOLD;
    }

    static boolean isDegraded(Map<String, Object> metrics, Thresholds thresholds) {
        Double battery = number(metrics.get(BATTERY_PCT));
        Double signal = number(metrics.get(SIGNAL_DBM));
        return (battery != null && battery < thresholds.batteryDegradedPct())
                || (signal != null && signal < thresholds.signalDegradedDbm());
    }

    private static Double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static boolean hasText(Object value) {
        return value instanceof String s && !s.isBlank();
    }

    private StatusModel() {
    }
}
