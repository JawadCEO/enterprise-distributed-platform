package com.edp.iot.state.model;

import com.edp.iot.common.change.ChangeKind;
import com.edp.iot.common.change.LineChange;
import com.edp.iot.common.event.EventType;
import com.edp.iot.common.event.NetworkEvent;
import com.edp.iot.common.event.OrderingKey;
import com.edp.iot.common.line.Connection;
import com.edp.iot.common.line.LineInfo;
import com.edp.iot.common.line.LineState;
import com.edp.iot.common.line.SimType;
import com.edp.iot.common.state.DeviceState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LineModelTest {

    static final Instant T0 = Instant.parse("2026-11-10T10:00:00Z");
    static final String ICCID = "8901260000000000001";
    static final String IMEI_A = "356938035643809";
    static final String IMEI_B = "490154203237518";

    static LineInfo line(SimType sim, boolean autoSuspend) {
        return new LineInfo(ICCID, "cust-1", sim, "TRACKER", LineState.ACTIVE, 300, 1_000_000, "local", 1000, autoSuspend);
    }

    static NetworkEvent device(EventType type, String imei, long boot, long seq, Instant receivedAt, Map<String, Object> payload) {
        return new NetworkEvent(UUID.randomUUID(), type, ICCID, imei, "cust-1", boot, seq, receivedAt, null, receivedAt, payload);
    }

    static NetworkEvent network(EventType type, String imei, Instant networkTime, Map<String, Object> payload) {
        return new NetworkEvent(UUID.randomUUID(), type, ICCID, imei, "cust-1", null, null, null, networkTime,
                networkTime.plusMillis(5), payload);
    }

    static LineModel.Result run(LineInfo line, DeviceState state, List<NetworkEvent> events) {
        Set<UUID> counted = new HashSet<>();
        events.stream().filter(e -> e.type() == EventType.USAGE_RECORD).forEach(e -> counted.add(e.eventId()));
        return LineModel.apply(new LineModel.Snapshot(line, line.lineState(), state, Map.of()), events, counted);
    }

    static List<ChangeKind> kinds(LineModel.Result r) {
        return r.changes().stream().map(LineChange::kind).toList();
    }

    static DeviceState online(String imei, OrderingKey key) {
        return new DeviceState(ICCID, "cust-1", Connection.ONLINE, "EVENT", T0, T0, imei, "local", key, T0, Map.of());
    }

    @Test
    void firstEventBringsTheLineOnline() {
        var r = run(line(SimType.REMOVABLE, false), null,
                List.of(device(EventType.KEEP_ALIVE, IMEI_A, 1, 1, T0, Map.of())));

        assertThat(r.state().connection()).isEqualTo(Connection.ONLINE);
        assertThat(r.state().imei()).isEqualTo(IMEI_A);
        assertThat(kinds(r)).containsExactly(ChangeKind.ONLINE);
        assertThat(r.changes().get(0).notifyCustomer()).isTrue();
    }

    @Test
    void higherBootWinsAfterARebootAndOlderEventsAreIgnored() {
        DeviceState s = online(IMEI_A, new OrderingKey(4, 900));
        var r = run(line(SimType.REMOVABLE, false), s, List.of(
                device(EventType.DEVICE_STATUS, IMEI_A, 5, 1, T0.plusSeconds(1), Map.of("batteryPct", 70)),
                device(EventType.DEVICE_STATUS, IMEI_A, 4, 950, T0.plusSeconds(2), Map.of("batteryPct", 10))));

        assertThat(r.state().deviceKey()).isEqualTo(new OrderingKey(5, 1));
        assertThat(r.state().metrics()).containsEntry("batteryPct", 70);
        assertThat(r.applied()).isEqualTo(1);
    }

    @Test
    void detachTakesTheLineOfflineAndAnOlderDetachDoesNot() {
        DeviceState s = online(IMEI_A, new OrderingKey(1, 1));
        var r = run(line(SimType.REMOVABLE, false), s, List.of(
                network(EventType.DEVICE_DETACHED, IMEI_A, T0.plusSeconds(60), Map.of()),
                network(EventType.DEVICE_ATTACHED, IMEI_A, T0.plusSeconds(30), Map.of())));

        assertThat(r.state().connection()).isEqualTo(Connection.OFFLINE);
        assertThat(r.state().connectionReason()).isEqualTo(LineModel.REASON_DETACHED);
        assertThat(kinds(r)).containsExactly(ChangeKind.DETACHED, ChangeKind.OFFLINE);
    }

    @Test
    void offlineChangesOnASuspendedLineAreRecordedButNotSent() {
        LineInfo line = line(SimType.REMOVABLE, false);
        DeviceState s = online(IMEI_A, new OrderingKey(1, 1));
        var r = LineModel.apply(new LineModel.Snapshot(line, LineState.SUSPENDED, s, Map.of()),
                List.of(network(EventType.DEVICE_DETACHED, IMEI_A, T0.plusSeconds(60), Map.of())), Set.of());

        LineChange offline = r.changes().stream().filter(c -> c.kind() == ChangeKind.OFFLINE).findFirst().orElseThrow();
        assertThat(offline.notifyCustomer()).isFalse();
    }

    @Test
    void aDeviceReconnectingDuringAProfileSwitchSendsNoAlert() {
        // FR-20, Flow E step 3: the line is PENDING while the switch runs, and the device drops off and comes back
        LineInfo line = line(SimType.EMBEDDED, false);
        DeviceState s = online(IMEI_A, new OrderingKey(1, 1));
        var r = LineModel.apply(new LineModel.Snapshot(line, LineState.PENDING, s, Map.of()),
                List.of(network(EventType.DEVICE_DETACHED, IMEI_A, T0.plusSeconds(60), Map.of()),
                        network(EventType.DEVICE_ATTACHED, IMEI_A, T0.plusSeconds(61), Map.of())), Set.of());

        assertThat(kinds(r)).contains(ChangeKind.OFFLINE, ChangeKind.ONLINE);
        assertThat(r.changes()).noneMatch(LineChange::notifyCustomer);
        assertThat(r.state().connection()).isEqualTo(com.edp.iot.common.line.Connection.ONLINE);
    }

    @Test
    void usageCountsOnlyNewRecordsAndCrossesEachThresholdOnce() {
        LineInfo line = line(SimType.REMOVABLE, false);
        NetworkEvent first = network(EventType.USAGE_RECORD, IMEI_A, T0, Map.of("bytesUp", 400_000, "bytesDown", 450_000));
        NetworkEvent repeat = first;
        NetworkEvent second = network(EventType.USAGE_RECORD, IMEI_A, T0.plusSeconds(1), Map.of("bytesUp", 100_000));

        var r = LineModel.apply(new LineModel.Snapshot(line, LineState.ACTIVE, online(IMEI_A, null), Map.of()),
                List.of(first, repeat, second), Set.of(first.eventId(), second.eventId()));

        UsageCycle cycle = r.cycles().get(LocalDate.parse("2026-11-01"));
        assertThat(cycle.bytesUsed()).isEqualTo(950_000);
        assertThat(cycle.notified80()).isTrue();
        assertThat(cycle.notified100()).isFalse();
        assertThat(kinds(r)).containsExactly(ChangeKind.USAGE_THRESHOLD);
    }

    @Test
    void thresholdAlreadySentThisCycleIsNotSentAgain() {
        LineInfo line = line(SimType.REMOVABLE, false);
        LocalDate cycleStart = LocalDate.parse("2026-11-01");
        UsageCycle cycle = new UsageCycle(ICCID, cycleStart, 850_000, 1_000_000, true, false);
        NetworkEvent more = network(EventType.USAGE_RECORD, IMEI_A, T0, Map.of("bytesUp", 10_000));

        var r = LineModel.apply(new LineModel.Snapshot(line, LineState.ACTIVE, online(IMEI_A, null), Map.of(cycleStart, cycle)),
                List.of(more), Set.of(more.eventId()));

        assertThat(r.changes()).isEmpty();
        assertThat(r.cycles().get(cycleStart).bytesUsed()).isEqualTo(860_000);
    }

    @Test
    void removableSimMoveIsMediumAndDoesNotSuspend() {
        DeviceState s = online(IMEI_A, new OrderingKey(3, 10));
        var r = run(line(SimType.REMOVABLE, true), s,
                List.of(device(EventType.KEEP_ALIVE, IMEI_B, 1, 1, T0.plusSeconds(5), Map.of())));

        assertThat(r.state().imei()).isEqualTo(IMEI_B);
        assertThat(r.state().deviceKey()).isEqualTo(new OrderingKey(1, 1));
        assertThat(r.securityEvents()).singleElement()
                .satisfies(e -> assertThat(e.severity()).isEqualTo(SecurityEvent.Severity.MEDIUM));
        assertThat(r.lineState()).isNull();
        assertThat(kinds(r)).containsExactly(ChangeKind.SIM_MOVED);
    }

    @Test
    void embeddedSimMoveIsHighAndSuspendsWhenTheCustomerOptedIn() {
        DeviceState s = online(IMEI_A, new OrderingKey(3, 10));
        var r = run(line(SimType.EMBEDDED, true), s,
                List.of(network(EventType.IMEI_CHANGED, IMEI_B, T0.plusSeconds(5), Map.of())));

        assertThat(r.securityEvents()).singleElement()
                .satisfies(e -> assertThat(e.severity()).isEqualTo(SecurityEvent.Severity.HIGH));
        assertThat(r.lineState()).isEqualTo(LineState.SUSPENDED);
        assertThat(kinds(r)).containsExactly(ChangeKind.SIM_MOVED, ChangeKind.LINE_STATE_CHANGED);
        // the new device starts its own (bootId, sequence) series
        assertThat(r.state().deviceKey()).isNull();
    }

    @Test
    void lateEventFromTheOldDeviceDoesNotMoveTheSimBack() {
        DeviceState movedToB = new DeviceState(ICCID, "cust-1", Connection.ONLINE, "EVENT", T0, T0.plusSeconds(10), IMEI_B,
                "local", new OrderingKey(1, 2), T0, Map.of());
        var r = run(line(SimType.REMOVABLE, false), movedToB,
                List.of(device(EventType.KEEP_ALIVE, IMEI_A, 3, 11, T0.plusSeconds(2), Map.of())));

        assertThat(r.state()).isNull();
        assertThat(r.securityEvents()).isEmpty();
    }

    @Test
    void faultIsReportedOnceAndCleared() {
        DeviceState s = online(IMEI_A, new OrderingKey(1, 1));
        var r = run(line(SimType.REMOVABLE, false), s, List.of(
                device(EventType.DEVICE_STATUS, IMEI_A, 1, 2, T0.plusSeconds(1), Map.of("faultCode", "E42")),
                device(EventType.DEVICE_STATUS, IMEI_A, 1, 3, T0.plusSeconds(2), Map.of("faultCode", "E42")),
                device(EventType.DEVICE_STATUS, IMEI_A, 1, 4, T0.plusSeconds(3), Map.of("batteryPct", 50))));

        assertThat(kinds(r)).containsExactly(ChangeKind.FAULT_REPORTED, ChangeKind.FAULT_CLEARED);
        assertThat(r.state().metrics()).doesNotContainKey("faultCode");
    }

    @Test
    void eventAfterOfflineMarkBringsTheLineBackOnline() {
        DeviceState offline = new DeviceState(ICCID, "cust-1", Connection.OFFLINE, LineModel.REASON_KEEP_ALIVE_MISSED,
                T0.plusSeconds(400), T0, IMEI_A, "local", new OrderingKey(1, 5), T0, Map.of());
        var stale = run(line(SimType.REMOVABLE, false), offline,
                List.of(device(EventType.KEEP_ALIVE, IMEI_A, 1, 6, T0.plusSeconds(300), Map.of())));
        var fresh = run(line(SimType.REMOVABLE, false), offline,
                List.of(device(EventType.KEEP_ALIVE, IMEI_A, 1, 6, T0.plusSeconds(500), Map.of())));

        assertThat(stale.state().connection()).isEqualTo(Connection.OFFLINE);
        assertThat(fresh.state().connection()).isEqualTo(Connection.ONLINE);
        assertThat(kinds(fresh)).containsExactly(ChangeKind.ONLINE);
    }

    @Test
    void lineMarkedOfflineDuringARegionOutageComesBackWithoutAnAlert() {
        DeviceState inOutage = new DeviceState(ICCID, "cust-1", Connection.OFFLINE, Connection.REASON_REGION_OUTAGE,
                T0.plusSeconds(60), T0, IMEI_A, "south", new OrderingKey(1, 5), T0, Map.of());
        var r = run(line(SimType.REMOVABLE, false), inOutage,
                List.of(network(EventType.DEVICE_ATTACHED, IMEI_A, T0.plusSeconds(120), Map.of("region", "south"))));

        assertThat(r.state().connection()).isEqualTo(Connection.ONLINE);
        LineChange online = r.changes().stream().filter(c -> c.kind() == ChangeKind.ONLINE).findFirst().orElseThrow();
        assertThat(online.notifyCustomer()).isFalse(); // the customer had one outage notice instead (FR-21)
    }

    @Test
    void lineThatWentSilentOnItsOwnComesBackWithAnAlert() {
        DeviceState silent = new DeviceState(ICCID, "cust-1", Connection.OFFLINE, LineModel.REASON_KEEP_ALIVE_MISSED,
                T0.plusSeconds(60), T0, IMEI_A, "south", new OrderingKey(1, 5), T0, Map.of());
        var r = run(line(SimType.REMOVABLE, false), silent,
                List.of(device(EventType.KEEP_ALIVE, IMEI_A, 1, 6, T0.plusSeconds(120), Map.of())));

        LineChange online = r.changes().stream().filter(c -> c.kind() == ChangeKind.ONLINE).findFirst().orElseThrow();
        assertThat(online.notifyCustomer()).isTrue();
    }
}
