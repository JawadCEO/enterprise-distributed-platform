package com.edp.iot.processor.pipeline;

import com.edp.iot.common.deadletter.ReasonCodes;
import com.edp.iot.common.event.TelemetryEvent;
import com.edp.iot.common.outbox.StatusChangeOutbox;
import com.edp.iot.common.registry.DeviceInfo;
import com.edp.iot.common.registry.DeviceRegistry;
import com.edp.iot.common.status.StatusChangeEvent;
import com.edp.iot.common.status.StatusModel;
import com.edp.iot.common.status.StatusState;
import com.edp.iot.processor.ProcessorProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Applies a batch of events (HLD 3.2). One transaction per batch:
 * <ol>
 *   <li>drop events from devices that are unknown or were deactivated after their ack (dead letter)</li>
 *   <li>insert telemetry rows for events not seen before and still inside retention</li>
 *   <li>lock the devices' status rows, fold each device's events in key order through the status
 *       model, and upsert the result with the ordering guard</li>
 *   <li>write status changes to the outbox</li>
 * </ol>
 * After commit it updates Redis and publishes the changes. A failure before commit leaves nothing
 * behind, so the whole batch can be retried.
 */
@Component
public class EventProcessor {

    public record DeadLetter(TelemetryEvent event, String reasonCode, String reason) {
    }

    public record Result(int stored, int statusUpdates, List<StatusChangeEvent> changes, List<DeadLetter> deadLetters) {
    }

    private record Committed(List<StatusState> statuses, List<StatusChangeEvent> changes) {
    }

    private final DeviceRegistry registry;
    private final TelemetryStore store;
    private final RedisState redis;
    private final StatusChangeOutbox outbox;
    private final TransactionTemplate tx;
    private final ProcessorProperties props;
    private final ProcessorMetrics metrics;

    public EventProcessor(DeviceRegistry registry, TelemetryStore store, RedisState redis, StatusChangeOutbox outbox,
                          TransactionTemplate tx, ProcessorProperties props, ProcessorMetrics metrics) {
        this.metrics = metrics;
        this.registry = registry;
        this.store = store;
        this.redis = redis;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
    }

    public Result process(List<TelemetryEvent> events) {
        Map<String, DeviceInfo> devices = registry.findAll(events.stream().map(TelemetryEvent::deviceId).distinct().toList());

        List<TelemetryEvent> valid = new ArrayList<>(events.size());
        List<DeadLetter> deadLetters = new ArrayList<>();
        for (TelemetryEvent e : events) {
            DeviceInfo device = devices.get(e.deviceId());
            if (device == null) {
                deadLetters.add(new DeadLetter(e, ReasonCodes.UNKNOWN_DEVICE, "Device is not registered"));
            } else if (!device.active()) {
                deadLetters.add(new DeadLetter(e, ReasonCodes.INACTIVE_DEVICE, "Device was deactivated"));
            } else {
                valid.add(e);
            }
        }
        if (valid.isEmpty()) {
            metrics.deadLettered(deadLetters.size());
            return new Result(0, 0, List.of(), deadLetters);
        }

        Set<String> seen = new HashSet<>(redis.alreadySeen(valid));
        Instant retentionStart = Instant.now().minus(props.telemetryRetention());
        List<TelemetryEvent> toStore = new ArrayList<>();
        for (TelemetryEvent e : valid) {
            // seen.add also drops a second copy of the same event within this batch
            if (e.receivedAt().isAfter(retentionStart) && seen.add(e.dedupId())) {
                toStore.add(e);
            }
        }

        Map<String, List<TelemetryEvent>> byDevice = new LinkedHashMap<>();
        for (TelemetryEvent e : valid) {
            byDevice.computeIfAbsent(e.deviceId(), k -> new ArrayList<>()).add(e);
        }

        Committed committed = tx.execute(status -> {
            store.insertTelemetry(toStore);
            Map<String, StatusState> current = store.lockStatuses(byDevice.keySet());
            List<StatusState> updates = new ArrayList<>();
            List<StatusChangeEvent> changes = new ArrayList<>();
            byDevice.forEach((deviceId, deviceEvents) -> {
                StatusState state = current.get(deviceId);
                boolean updated = false;
                List<TelemetryEvent> ordered = new ArrayList<>(deviceEvents);
                ordered.sort(Comparator.comparing(TelemetryEvent::key));
                for (TelemetryEvent e : ordered) {
                    Optional<StatusModel.Applied> applied = StatusModel.apply(state, e, devices.get(deviceId).thresholds());
                    if (applied.isPresent()) {
                        state = applied.get().state();
                        applied.get().change().ifPresent(changes::add);
                        updated = true;
                    }
                }
                if (updated) {
                    updates.add(state);
                }
            });
            store.upsertStatuses(updates);
            outbox.insert(changes);
            return new Committed(updates, changes);
        });

        redis.afterCommit(toStore, committed.statuses(), deadlines(byDevice, devices));
        metrics.committed(valid.stream().map(TelemetryEvent::receivedAt).toList(), toStore.size(), valid.size(),
                committed.changes().size());
        metrics.deadLettered(deadLetters.size());
        outbox.publishCommitted(committed.changes());
        return new Result(toStore.size(), committed.statuses().size(), committed.changes(), deadLetters);
    }

    /** Offline deadline per device: latest receive time in the batch + heartbeat window + grace. */
    private Map<String, Instant> deadlines(Map<String, List<TelemetryEvent>> byDevice, Map<String, DeviceInfo> devices) {
        Map<String, Instant> deadlines = new HashMap<>();
        byDevice.forEach((deviceId, deviceEvents) -> {
            Instant latest = deviceEvents.stream().map(TelemetryEvent::receivedAt).max(Instant::compareTo).orElseThrow();
            deadlines.put(deviceId, latest.plus(devices.get(deviceId).heartbeatWindow()).plus(props.presenceGrace()));
        });
        return deadlines;
    }
}
