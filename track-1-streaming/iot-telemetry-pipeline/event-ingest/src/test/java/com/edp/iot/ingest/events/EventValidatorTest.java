package com.edp.iot.ingest.events;

import com.edp.iot.common.Json;
import com.edp.iot.common.event.EventType;
import com.edp.iot.ingest.IngestProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventValidatorTest {

    static final IngestProperties PROPS = new IngestProperties(100, 262144, 4096, Duration.ofHours(24), Duration.ofDays(7),
            10, 1, 20000, Duration.ofSeconds(2), Duration.ofSeconds(30), 1000, 12, (short) 1, 1);
    static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    static final String ICCID = "8901260000000000001";

    private final EventValidator validator = new EventValidator(PROPS);

    @Test
    void deviceStatusKeepsKnownFieldsAndDropsUnknownOnes() {
        var outcome = validator.validate(node("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","type":"DEVICE_STATUS","lineId":"%s",
                 "imei":"356938035643809","bootId":3,"sequence":7,"deviceTime":"2026-11-10T09:59:59Z",
                 "payload":{"batteryPct":81,"signalDbm":-71.5,"firmware":"4.2.1","colour":"blue"}}
                """.formatted(ICCID)), NOW);

        assertThat(outcome).isInstanceOf(EventValidator.Valid.class);
        var event = ((EventValidator.Valid) outcome).event();
        assertThat(event.type()).isEqualTo(EventType.DEVICE_STATUS);
        assertThat(event.receivedAt()).isEqualTo(NOW);
        assertThat(event.customerId()).isNull();
        assertThat(event.payload()).containsOnlyKeys("batteryPct", "signalDbm", "firmware");
    }

    @Test
    void usageRecordNeedsNetworkTimeAndWholeBytes() {
        var outcome = validator.validate(node("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","type":"USAGE_RECORD","lineId":"%s",
                 "networkTime":"2026-11-10T09:59:00Z","payload":{"bytesUp":1200,"bytesDown":34000}}
                """.formatted(ICCID)), NOW);

        assertThat(outcome).isInstanceOf(EventValidator.Valid.class);
        assertThat(((EventValidator.Valid) outcome).event().usageBytes()).isEqualTo(35200);
    }

    @Test
    void rejectsWithMachineReadableCodes() {
        assertThat(code("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","type":"KEEP_ALIVE","lineId":"%s",
                 "imei":"356938035643809","bootId":1,"deviceTime":"2026-11-10T09:59:59Z"}""")).isEqualTo("INVALID_SEQUENCE");
        assertThat(code("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","type":"KEEP_ALIVE","lineId":"12345",
                 "imei":"356938035643809","bootId":1,"sequence":1,"deviceTime":"2026-11-10T09:59:59Z"}""")).isEqualTo("INVALID_LINE_ID");
        assertThat(code("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","type":"KEEP_ALIVE","lineId":"%s",
                 "bootId":1,"sequence":1,"deviceTime":"2026-11-10T09:59:59Z"}""")).isEqualTo("INVALID_IMEI");
        assertThat(code("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","type":"DEVICE_STATUS","lineId":"%s",
                 "imei":"356938035643809","bootId":1,"sequence":1,"deviceTime":"2026-11-12T11:00:00Z"}""")).isEqualTo("TIME_IN_FUTURE");
        assertThat(code("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","type":"DEVICE_STATUS","lineId":"%s",
                 "imei":"356938035643809","bootId":1,"sequence":1,"deviceTime":"2026-11-10T09:59:59Z",
                 "payload":{"batteryPct":"low"}}""")).isEqualTo("INVALID_PAYLOAD_FIELD");
        assertThat(code("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","type":"USAGE_RECORD","lineId":"%s",
                 "networkTime":"2026-10-01T09:59:00Z","payload":{"bytesUp":1}}""")).isEqualTo("EVENT_TOO_OLD");
        assertThat(code("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","type":"USAGE_RECORD","lineId":"%s",
                 "networkTime":"2026-11-10T09:59:00Z","payload":{}}""")).isEqualTo("INVALID_PAYLOAD_FIELD");
        assertThat(code("""
                {"eventId":"not-a-uuid","type":"KEEP_ALIVE","lineId":"%s"}""")).isEqualTo("INVALID_EVENT_ID");
        assertThat(code("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","type":"HEARTBEAT","lineId":"%s"}""")).isEqualTo("INVALID_TYPE");
    }

    private String code(String json) {
        var outcome = validator.validate(node(json.formatted(ICCID)), NOW);
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
