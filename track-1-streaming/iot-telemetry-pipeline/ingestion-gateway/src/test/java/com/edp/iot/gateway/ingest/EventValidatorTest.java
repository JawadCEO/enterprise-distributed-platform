package com.edp.iot.gateway.ingest;

import com.edp.iot.common.Json;
import com.edp.iot.common.registry.DeviceInfo;
import com.edp.iot.common.status.Thresholds;
import com.edp.iot.gateway.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventValidatorTest {

    static final GatewayProperties PROPS = new GatewayProperties(100, 262144, 4096, Duration.ofHours(24), 10, 1, 20000,
            Duration.ofSeconds(2), Duration.ofSeconds(30), 1000, 12, (short) 1, 1);
    static final DeviceInfo DEVICE = new DeviceInfo("dev-1", "tenant-1", "TRACKER", true, "hash", 60, "local", 1000,
            Thresholds.DEFAULT);
    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    private final EventValidator validator = new EventValidator(PROPS);

    @Test
    void validEventGetsTenantAndReceiveTimeAndDropsUnknownMetrics() {
        var outcome = validator.validate(node("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","deviceId":"dev-1","bootId":3,"sequence":7,
                 "deviceTime":"2026-11-04T09:59:59Z","type":"TELEMETRY",
                 "metrics":{"batteryPct":81,"signalDbm":-71.5,"firmware":"4.2.1","colour":"blue"}}
                """), DEVICE, NOW);

        assertThat(outcome).isInstanceOf(EventValidator.Valid.class);
        var event = ((EventValidator.Valid) outcome).event();
        assertThat(event.tenantId()).isEqualTo("tenant-1");
        assertThat(event.receivedAt()).isEqualTo(NOW);
        assertThat(event.metrics()).containsOnlyKeys("batteryPct", "signalDbm", "firmware");
    }

    @Test
    void rejectsWithMachineReadableCodes() {
        assertThat(code("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","deviceId":"dev-1","bootId":1,
                 "deviceTime":"2026-11-04T09:59:59Z","type":"TELEMETRY"}""")).isEqualTo("INVALID_SEQUENCE");
        assertThat(code("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","deviceId":"dev-2","bootId":1,"sequence":1,
                 "deviceTime":"2026-11-04T09:59:59Z","type":"TELEMETRY"}""")).isEqualTo("DEVICE_MISMATCH");
        assertThat(code("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","deviceId":"dev-1","bootId":1,"sequence":1,
                 "deviceTime":"2026-11-06T11:00:00Z","type":"TELEMETRY"}""")).isEqualTo("DEVICE_TIME_IN_FUTURE");
        assertThat(code("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","deviceId":"dev-1","bootId":1,"sequence":1,
                 "deviceTime":"2026-11-04T09:59:59Z","type":"TELEMETRY","metrics":{"batteryPct":"low"}}"""))
                .isEqualTo("INVALID_METRIC_TYPE");
        assertThat(code("""
                {"eventId":"not-a-uuid","deviceId":"dev-1","bootId":1,"sequence":1,
                 "deviceTime":"2026-11-04T09:59:59Z","type":"TELEMETRY"}""")).isEqualTo("INVALID_EVENT_ID");
    }

    private String code(String json) {
        var outcome = validator.validate(node(json), DEVICE, NOW);
        assertThat(outcome).isInstanceOf(EventValidator.Invalid.class);
        return ((EventValidator.Invalid) outcome).code();
    }

    static JsonNode node(String json) {
        try {
            return Json.MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
