package com.edp.iot.common.change;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row of {@code line_history} and one message on {@code line.changes}. Delivery is at least
 * once (outbox relay), so consumers de-duplicate on {@code changeId}.
 *
 * @param notifyCustomer whether the customer is told (FR-20: expected silence is recorded, not alerted)
 * @param eventId  the event that caused the change, or null for changes made by presence-monitor
 * @param details  kind-specific facts, such as {@code reason}, {@code from}, {@code to},
 *                 {@code thresholdPct}, {@code severity}, {@code oldImei}, {@code newImei}
 */
public record LineChange(
        UUID changeId,
        String iccid,
        String customerId,
        ChangeKind kind,
        boolean notifyCustomer,
        UUID eventId,
        Instant at,
        Map<String, Object> details) {

    public LineChange {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static LineChange of(String iccid, String customerId, ChangeKind kind, boolean notifyCustomer, UUID eventId,
                                Instant at, Map<String, Object> details) {
        return new LineChange(UUID.randomUUID(), iccid, customerId, kind, notifyCustomer, eventId, at, details);
    }
}
