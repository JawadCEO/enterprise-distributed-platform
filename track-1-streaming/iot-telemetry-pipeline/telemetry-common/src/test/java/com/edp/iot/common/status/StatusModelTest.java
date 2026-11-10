package com.edp.iot.common.status;

import com.edp.iot.common.event.EventType;
import com.edp.iot.common.event.OrderingKey;
import com.edp.iot.common.event.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatusModelTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void firstEventCreatesOnlineStatus() {
        var applied = StatusModel.apply(null, event(1, 1, EventType.TELEMETRY, Map.of("batteryPct", 80)), Thresholds.DEFAULT);

        assertThat(applied).isPresent();
        assertThat(applied.get().state().status()).isEqualTo(DeviceStatus.ONLINE);
        assertThat(applied.get().change()).get().extracting(StatusChangeEvent::cause).isEqualTo(ChangeCause.EVENT);
    }

    @Test
    void staleAndDuplicateEventsAreIgnored() {
        StatusState current = state(DeviceStatus.ONLINE, 3, 50);

        assertThat(StatusModel.apply(current, event(3, 50, EventType.TELEMETRY, Map.of()), Thresholds.DEFAULT)).isEmpty();
        assertThat(StatusModel.apply(current, event(3, 49, EventType.TELEMETRY, Map.of()), Thresholds.DEFAULT)).isEmpty();
        assertThat(StatusModel.apply(current, event(2, 900, EventType.TELEMETRY, Map.of()), Thresholds.DEFAULT)).isEmpty();
    }

    @Test
    void rebootWithResetSequenceIsAppliedAndBringsDeviceBackFromOffline() {
        StatusState offline = state(DeviceStatus.OFFLINE, 3, 98_231);

        Optional<StatusModel.Applied> applied =
                StatusModel.apply(offline, event(4, 0, EventType.HEARTBEAT, Map.of()), Thresholds.DEFAULT);

        assertThat(applied).isPresent();
        assertThat(applied.get().state().key()).isEqualTo(new OrderingKey(4, 0));
        assertThat(applied.get().state().status()).isEqualTo(DeviceStatus.ONLINE);
        assertThat(applied.get().change()).get().extracting(StatusChangeEvent::cause).isEqualTo(ChangeCause.REBOOT);
    }

    @Test
    void lowBatteryDegradesAndRecoveryRestoresOnline() {
        var degraded = StatusModel.apply(state(DeviceStatus.ONLINE, 1, 1),
                event(1, 2, EventType.TELEMETRY, Map.of("batteryPct", 9)), Thresholds.DEFAULT).orElseThrow();
        assertThat(degraded.state().status()).isEqualTo(DeviceStatus.DEGRADED);
        assertThat(degraded.change()).get().extracting(StatusChangeEvent::cause).isEqualTo(ChangeCause.THRESHOLD);

        var recovered = StatusModel.apply(degraded.state(),
                event(1, 3, EventType.TELEMETRY, Map.of("batteryPct", 95)), Thresholds.DEFAULT).orElseThrow();
        assertThat(recovered.state().status()).isEqualTo(DeviceStatus.ONLINE);
    }

    @Test
    void heartbeatKeepsLastKnownMetricsForDegradation() {
        var degraded = StatusModel.apply(state(DeviceStatus.ONLINE, 1, 1),
                event(1, 2, EventType.TELEMETRY, Map.of("signalDbm", -120)), Thresholds.DEFAULT).orElseThrow();

        var afterHeartbeat = StatusModel.apply(degraded.state(),
                event(1, 3, EventType.HEARTBEAT, Map.of()), Thresholds.DEFAULT).orElseThrow();

        assertThat(afterHeartbeat.state().status()).isEqualTo(DeviceStatus.DEGRADED);
        assertThat(afterHeartbeat.change()).isEmpty();
    }

    @Test
    void faultPersistsUntilStatusEventClearsIt() {
        var fault = StatusModel.apply(state(DeviceStatus.ONLINE, 1, 1),
                event(1, 2, EventType.STATUS, Map.of("faultCode", "E42")), Thresholds.DEFAULT).orElseThrow();
        assertThat(fault.state().status()).isEqualTo(DeviceStatus.FAULT);
        assertThat(fault.change()).get().extracting(StatusChangeEvent::cause).isEqualTo(ChangeCause.FAULT_REPORTED);

        var stillFault = StatusModel.apply(fault.state(),
                event(1, 3, EventType.TELEMETRY, Map.of("batteryPct", 80)), Thresholds.DEFAULT).orElseThrow();
        assertThat(stillFault.state().status()).isEqualTo(DeviceStatus.FAULT);

        var cleared = StatusModel.apply(stillFault.state(),
                event(1, 4, EventType.STATUS, Map.of()), Thresholds.DEFAULT).orElseThrow();
        assertThat(cleared.state().status()).isEqualTo(DeviceStatus.ONLINE);
        assertThat(cleared.state().metrics()).doesNotContainKey("faultCode");
        assertThat(cleared.change()).get().extracting(StatusChangeEvent::cause).isEqualTo(ChangeCause.FAULT_CLEARED);
    }

    private static StatusState state(DeviceStatus status, long boot, long seq) {
        return new StatusState("dev-1", "tenant-1", status, new OrderingKey(boot, seq), T0, T0, Map.of("batteryPct", 80));
    }

    private static TelemetryEvent event(long boot, long seq, EventType type, Map<String, Object> metrics) {
        return new TelemetryEvent(UUID.randomUUID(), "dev-1", "tenant-1", boot, seq, T0, T0.plusSeconds(seq), type, metrics);
    }
}
