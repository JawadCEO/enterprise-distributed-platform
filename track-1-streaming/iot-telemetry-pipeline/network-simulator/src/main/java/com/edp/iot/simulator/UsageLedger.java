package com.edp.iot.simulator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Usage the platform acknowledged, per line. Keyed by event ID, so a usage record that was sent
 * twice (a retry, or an injected duplicate) counts once here, the same as it must in the platform.
 */
final class UsageLedger {

    private final Map<String, Map<String, Long>> byLine = new ConcurrentHashMap<>();

    void acked(String iccid, String eventId, long bytes) {
        byLine.computeIfAbsent(iccid, k -> new ConcurrentHashMap<>()).put(eventId, bytes);
    }

    long bytes(String iccid) {
        Map<String, Long> events = byLine.get(iccid);
        return events == null ? 0 : events.values().stream().mapToLong(Long::longValue).sum();
    }

    long totalBytes() {
        return byLine.keySet().stream().mapToLong(this::bytes).sum();
    }

    long records() {
        return byLine.values().stream().mapToLong(Map::size).sum();
    }
}
