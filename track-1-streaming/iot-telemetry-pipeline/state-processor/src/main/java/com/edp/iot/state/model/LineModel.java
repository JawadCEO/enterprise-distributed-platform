package com.edp.iot.state.model;

import com.edp.iot.common.change.ChangeKind;
import com.edp.iot.common.change.LineChange;
import com.edp.iot.common.event.EventType;
import com.edp.iot.common.event.NetworkEvent;
import com.edp.iot.common.event.OrderingKey;
import com.edp.iot.common.line.Connection;
import com.edp.iot.common.line.LineInfo;
import com.edp.iot.common.line.LineState;
import com.edp.iot.common.state.DeviceState;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies one line's events, in the order they were read from Kafka, to its stored state
 * (data.md sections 3 and 5). Pure: no I/O, so every rule is unit tested.
 *
 * <h2>Ordering (ADR-005)</h2>
 * <ul>
 *   <li>Device events ({@code DEVICE_STATUS}, {@code KEEP_ALIVE}) from the device in the line's
 *       current IMEI are applied only if their {@code (bootId, sequence)} is newer.</li>
 *   <li>A device event from a different IMEI comes from another device: the SIM moved. It wins
 *       only if it was received no earlier than the last event applied, so a late event from the
 *       old device can't move the SIM back.</li>
 *   <li>Network events ({@code DEVICE_ATTACHED}, {@code DEVICE_DETACHED}, {@code IMEI_CHANGED}) are
 *       applied only if their network time is not older than the last applied network event.</li>
 *   <li>{@code USAGE_RECORD} is additive and not ordered: it counts once if its {@code eventId} was
 *       inserted for the first time ({@code counted}), whenever it arrives (ADR-006).</li>
 *   <li>Connection changes also need the event's own clock (receive time for device events,
 *       network time for network events) to be after the last connection change, so an old
 *       event can't undo a newer detach or offline mark.</li>
 * </ul>
 */
public final class LineModel {

    public static final String REASON_EVENT = "EVENT";
    public static final String REASON_ATTACHED = "ATTACHED";
    public static final String REASON_DETACHED = "DETACHED";
    public static final String REASON_KEEP_ALIVE_MISSED = "KEEP_ALIVE_MISSED";
    public static final String REASON_SIM_MOVED = "SIM_MOVED";

    /** The line as locked in the database for this batch. */
    public record Snapshot(LineInfo line, LineState lineState, DeviceState state, Map<LocalDate, UsageCycle> cycles) {
    }

    /**
     * @param state           new device state, or null if no event changed it
     * @param lineState       new line state, or null if it did not change
     * @param cycles          usage cycles that changed
     * @param applied         number of events that changed something
     */
    public record Result(DeviceState state, LineState lineState, Map<LocalDate, UsageCycle> cycles,
                         List<LineChange> changes, List<SecurityEvent> securityEvents, int applied) {
    }

    public static Result apply(Snapshot snapshot, List<NetworkEvent> events, Set<UUID> counted) {
        return new LineModel(snapshot).run(events, counted);
    }

    private final LineInfo line;
    private final LineState originalLineState;
    private LineState lineState;
    private DeviceState state;
    private boolean stateChanged;
    private final Map<LocalDate, UsageCycle> cycles;
    private final Map<LocalDate, UsageCycle> changedCycles = new LinkedHashMap<>();
    private final List<LineChange> changes = new ArrayList<>();
    private final List<SecurityEvent> securityEvents = new ArrayList<>();
    private int applied;

    private LineModel(Snapshot snapshot) {
        this.line = snapshot.line();
        this.originalLineState = snapshot.lineState();
        this.lineState = snapshot.lineState();
        this.state = snapshot.state();
        this.cycles = new HashMap<>(snapshot.cycles() == null ? Map.of() : snapshot.cycles());
    }

    private Result run(List<NetworkEvent> events, Set<UUID> counted) {
        Set<UUID> toCount = new HashSet<>(counted);
        for (NetworkEvent e : events) {
            boolean changed = switch (e.type()) {
                case USAGE_RECORD -> usage(e, toCount.remove(e.eventId()));
                case DEVICE_STATUS, KEEP_ALIVE -> deviceEvent(e);
                case DEVICE_ATTACHED, DEVICE_DETACHED, IMEI_CHANGED -> networkEvent(e);
            };
            if (changed) {
                applied++;
            }
        }
        return new Result(stateChanged ? state : null, lineState != originalLineState ? lineState : null,
                changedCycles, changes, securityEvents, applied);
    }

    // ---- device events -------------------------------------------------------------------------

    private boolean deviceEvent(NetworkEvent e) {
        OrderingKey key = e.deviceKey();
        if (state == null) {
            state = newState(e);
            state = withDevice(state, e.imei(), key, e.receivedAt());
            mergeMetrics(e);
            goOnline(e.receivedAt(), REASON_EVENT, e.eventId());
            return true;
        }
        boolean otherDevice = state.imei() != null && e.imei() != null && !e.imei().equals(state.imei());
        if (otherDevice) {
            if (e.receivedAt().isBefore(state.lastSeen())) {
                return false; // late event from a device the SIM has left
            }
            simMoved(state.imei(), e.imei(), e.eventId(), e.receivedAt());
        } else if (!key.isNewerThan(state.deviceKey())) {
            seen(e);
            return false; // stale or repeated
        }
        state = withDevice(state, e.imei() == null ? state.imei() : e.imei(), key, e.receivedAt());
        mergeMetrics(e);
        if (state.connection() == Connection.OFFLINE && e.receivedAt().isAfter(state.connectionSince())) {
            goOnline(e.receivedAt(), REASON_EVENT, e.eventId());
        }
        return true;
    }

    private void mergeMetrics(NetworkEvent e) {
        if (e.type() != EventType.DEVICE_STATUS) {
            return;
        }
        Map<String, Object> metrics = new HashMap<>(state.metrics());
        Object previousFault = metrics.get(NetworkEvent.FAULT_CODE);
        for (String name : List.of(NetworkEvent.BATTERY_PCT, NetworkEvent.SIGNAL_DBM, NetworkEvent.TEMPERATURE_C,
                NetworkEvent.FIRMWARE)) {
            Object value = e.payload().get(name);
            if (value != null) {
                metrics.put(name, value);
            }
        }
        Object fault = e.payload().get(NetworkEvent.FAULT_CODE);
        if (fault instanceof String code && !code.isBlank()) {
            metrics.put(NetworkEvent.FAULT_CODE, code);
            if (!code.equals(previousFault)) {
                change(ChangeKind.FAULT_REPORTED, false, e.eventId(), e.receivedAt(), Map.of("faultCode", code));
            }
        } else if (previousFault != null) {
            metrics.remove(NetworkEvent.FAULT_CODE);
            change(ChangeKind.FAULT_CLEARED, false, e.eventId(), e.receivedAt(), Map.of("faultCode", previousFault));
        }
        state = copy(state, state.connection(), state.connectionReason(), state.connectionSince(), state.imei(),
                state.region(), state.deviceKey(), state.lastNetworkTime(), metrics);
        stateChanged = true;
    }

    // ---- network events ------------------------------------------------------------------------

    private boolean networkEvent(NetworkEvent e) {
        Instant at = e.networkTime();
        if (state == null) {
            state = newState(e);
            stateChanged = true;
        } else if (state.lastNetworkTime() != null && at.isBefore(state.lastNetworkTime())) {
            seen(e);
            return false; // older than the last network event applied
        }
        String imei = e.imei() == null ? state.imei() : e.imei();
        boolean moved = state.imei() != null && e.imei() != null && !e.imei().equals(state.imei());
        if (moved) {
            simMoved(state.imei(), e.imei(), e.eventId(), at);
        }
        String region = state.region();
        if (e.type() == EventType.DEVICE_ATTACHED && e.payload().get(NetworkEvent.REGION) instanceof String r) {
            region = r;
        }
        // A new device starts its own (bootId, sequence) series, so the old device's key no longer applies.
        OrderingKey key = moved ? null : state.deviceKey();
        Instant lastSeen = e.type() == EventType.DEVICE_DETACHED ? state.lastSeen() : later(state.lastSeen(), e.receivedAt());
        state = new DeviceState(state.iccid(), state.customerId(), state.connection(), state.connectionReason(),
                state.connectionSince(), lastSeen, imei, region, key, at, state.metrics());
        stateChanged = true;

        switch (e.type()) {
            case DEVICE_ATTACHED -> {
                change(ChangeKind.ATTACHED, false, e.eventId(), at, region == null ? Map.of() : Map.of("region", region));
                if (state.connection() == Connection.OFFLINE && at.isAfter(state.connectionSince())) {
                    goOnline(at, REASON_ATTACHED, e.eventId());
                }
            }
            case DEVICE_DETACHED -> {
                change(ChangeKind.DETACHED, false, e.eventId(), at, Map.of());
                if (state.connection() == Connection.ONLINE && at.isAfter(state.connectionSince())) {
                    goOffline(at, REASON_DETACHED, e.eventId());
                }
            }
            default -> {
                // IMEI_CHANGED: the move itself was handled above
            }
        }
        return true;
    }

    // ---- usage ---------------------------------------------------------------------------------

    private boolean usage(NetworkEvent e, boolean firstTime) {
        if (state == null) {
            state = newState(e);
            stateChanged = true;
        } else {
            seen(e);
        }
        if (!firstTime) {
            return false; // already counted (ADR-006)
        }
        LocalDate cycleStart = UsageCycle.cycleOf(e.networkTime());
        UsageCycle cycle = cycles.getOrDefault(cycleStart, UsageCycle.empty(line.iccid(), cycleStart, line.dataAllowanceBytes()))
                .plus(e.usageBytes());
        boolean at80 = cycle.notified80();
        boolean at100 = cycle.notified100();
        if (cycle.allowanceBytes() > 0) {
            if (!at80 && cycle.percentUsed() >= 80) {
                at80 = true;
                threshold(80, cycle, e);
            }
            if (!at100 && cycle.percentUsed() >= 100) {
                at100 = true;
                threshold(100, cycle, e);
            }
        }
        cycle = cycle.withNotified(at80, at100);
        cycles.put(cycleStart, cycle);
        changedCycles.put(cycleStart, cycle);
        return true;
    }

    private void threshold(int pct, UsageCycle cycle, NetworkEvent e) {
        change(ChangeKind.USAGE_THRESHOLD, true, e.eventId(), e.networkTime(), Map.of(
                "thresholdPct", pct,
                "cycleStart", cycle.cycleStart().toString(),
                "bytesUsed", cycle.bytesUsed(),
                "allowanceBytes", cycle.allowanceBytes()));
    }

    // ---- shared rules --------------------------------------------------------------------------

    private void simMoved(String oldImei, String newImei, UUID eventId, Instant at) {
        boolean high = !line.simType().canBeMovedByHand();
        boolean suspend = high && line.autoSuspendOnSimMove() && lineState == LineState.ACTIVE;
        SecurityEvent.Severity severity = high ? SecurityEvent.Severity.HIGH : SecurityEvent.Severity.MEDIUM;
        securityEvents.add(new SecurityEvent(UUID.randomUUID(), line.iccid(), line.customerId(), line.simType().name(),
                oldImei, newImei, severity, suspend, eventId, at));
        change(ChangeKind.SIM_MOVED, true, eventId, at, Map.of(
                "oldImei", oldImei, "newImei", newImei, "severity", severity.name(), "simType", line.simType().name(),
                "autoSuspended", suspend));
        if (suspend) {
            lineState = LineState.SUSPENDED;
            change(ChangeKind.LINE_STATE_CHANGED, true, eventId, at,
                    Map.of("from", LineState.ACTIVE.name(), "to", LineState.SUSPENDED.name(), "reason", REASON_SIM_MOVED));
        }
    }

    private void goOnline(Instant at, String reason, UUID eventId) {
        state = copy(state, Connection.ONLINE, reason, at, state.imei(), state.region(), state.deviceKey(),
                state.lastNetworkTime(), state.metrics());
        stateChanged = true;
        change(ChangeKind.ONLINE, alerting(), eventId, at, Map.of("reason", reason));
    }

    private void goOffline(Instant at, String reason, UUID eventId) {
        state = copy(state, Connection.OFFLINE, reason, at, state.imei(), state.region(), state.deviceKey(),
                state.lastNetworkTime(), state.metrics());
        stateChanged = true;
        change(ChangeKind.OFFLINE, alerting(), eventId, at, Map.of("reason", reason));
    }

    /** FR-20: only an active line's connection changes reach the customer. */
    private boolean alerting() {
        return lineState == LineState.ACTIVE;
    }

    private void change(ChangeKind kind, boolean notifyCustomer, UUID eventId, Instant at, Map<String, Object> details) {
        changes.add(LineChange.of(line.iccid(), line.customerId(), kind, notifyCustomer, eventId, at, details));
    }

    /** Any event except a detach is a sign of life, even when it is too old to change state. */
    private void seen(NetworkEvent e) {
        if (e.type() == EventType.DEVICE_DETACHED || !e.receivedAt().isAfter(state.lastSeen())) {
            return;
        }
        state = new DeviceState(state.iccid(), state.customerId(), state.connection(), state.connectionReason(),
                state.connectionSince(), e.receivedAt(), state.imei(), state.region(), state.deviceKey(),
                state.lastNetworkTime(), state.metrics());
        stateChanged = true;
    }

    private DeviceState newState(NetworkEvent e) {
        // OFFLINE since the beginning of time, so the first sign of life brings the line online.
        return new DeviceState(line.iccid(), line.customerId(), Connection.OFFLINE, null, Instant.EPOCH,
                e.type() == EventType.DEVICE_DETACHED ? Instant.EPOCH : e.receivedAt(), null, null, null, null, Map.of());
    }

    private DeviceState withDevice(DeviceState s, String imei, OrderingKey key, Instant receivedAt) {
        stateChanged = true;
        return new DeviceState(s.iccid(), s.customerId(), s.connection(), s.connectionReason(), s.connectionSince(), later(s.lastSeen(), receivedAt), imei, s.region(), key,
                s.lastNetworkTime(), s.metrics());
    }

    private static DeviceState copy(DeviceState s, Connection connection, String reason, Instant since, String imei,
                                    String region, OrderingKey key, Instant lastNetworkTime, Map<String, Object> metrics) {
        return new DeviceState(s.iccid(), s.customerId(), connection, reason, since, s.lastSeen(), imei, region, key,
                lastNetworkTime, metrics);
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || !b.isAfter(a) ? a : b;
    }

}
