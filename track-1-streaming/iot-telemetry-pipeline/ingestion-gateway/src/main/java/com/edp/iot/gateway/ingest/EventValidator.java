package com.edp.iot.gateway.ingest;

import com.edp.iot.common.event.EventType;
import com.edp.iot.common.event.TelemetryEvent;
import com.edp.iot.common.registry.DeviceInfo;
import com.edp.iot.gateway.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Checks one event's shape (FR-3) and turns it into a {@link TelemetryEvent} with the gateway's
 * fields filled in. Unknown metric names are dropped, not rejected, so devices can add metrics
 * before the platform knows them.
 */
@Component
public class EventValidator {

    /** Known metrics and their allowed ranges. Strings carry a maximum length instead. */
    private static final Map<String, double[]> NUMERIC_METRICS = Map.of(
            "batteryPct", new double[]{0, 100},
            "signalDbm", new double[]{-200, 0},
            "temperatureC", new double[]{-100, 200},
            "humidityPct", new double[]{0, 100});
    private static final Map<String, Integer> TEXT_METRICS = Map.of(
            "firmware", 32,
            TelemetryEvent.FAULT_CODE, 32);

    private final GatewayProperties props;

    public EventValidator(GatewayProperties props) {
        this.props = props;
    }

    public sealed interface Outcome permits Valid, Invalid {
    }

    public record Valid(TelemetryEvent event) implements Outcome {
    }

    public record Invalid(Long sequence, String code, String message) implements Outcome {
    }

    public Outcome validate(JsonNode node, DeviceInfo device, Instant receivedAt) {
        if (node == null || !node.isObject()) {
            return new Invalid(null, "NOT_AN_OBJECT", "Event must be a JSON object");
        }
        Long sequence = node.path("sequence").canConvertToLong() && node.path("sequence").isIntegralNumber()
                ? node.path("sequence").asLong() : null;
        if (sequence == null || sequence < 0) {
            return new Invalid(null, "INVALID_SEQUENCE", "sequence must be a non-negative integer");
        }
        JsonNode boot = node.path("bootId");
        if (!boot.isIntegralNumber() || boot.asLong() < 0) {
            return new Invalid(sequence, "INVALID_BOOT_ID", "bootId must be a non-negative integer");
        }
        String deviceId = node.path("deviceId").asText(null);
        if (!device.deviceId().equals(deviceId)) {
            return new Invalid(sequence, "DEVICE_MISMATCH", "deviceId must match the authenticated device");
        }
        UUID eventId;
        try {
            eventId = UUID.fromString(node.path("eventId").asText(""));
        } catch (IllegalArgumentException e) {
            return new Invalid(sequence, "INVALID_EVENT_ID", "eventId must be a UUID");
        }
        EventType type;
        try {
            type = EventType.valueOf(node.path("type").asText(""));
        } catch (IllegalArgumentException e) {
            return new Invalid(sequence, "INVALID_TYPE", "type must be TELEMETRY, STATUS or HEARTBEAT");
        }
        Instant deviceTime;
        try {
            deviceTime = Instant.parse(node.path("deviceTime").asText(""));
        } catch (DateTimeParseException e) {
            return new Invalid(sequence, "INVALID_DEVICE_TIME", "deviceTime must be an ISO-8601 instant");
        }
        if (deviceTime.isAfter(receivedAt.plus(props.maxFutureSkew()))) {
            return new Invalid(sequence, "DEVICE_TIME_IN_FUTURE", "deviceTime is too far in the future");
        }

        JsonNode metricsNode = node.path("metrics");
        if (!metricsNode.isMissingNode() && !metricsNode.isNull() && !metricsNode.isObject()) {
            return new Invalid(sequence, "INVALID_METRICS", "metrics must be an object");
        }
        if (metricsNode.isObject() && metricsNode.toString().getBytes(StandardCharsets.UTF_8).length > props.maxMetricsBytes()) {
            return new Invalid(sequence, "METRICS_TOO_LARGE", "metrics exceed " + props.maxMetricsBytes() + " bytes");
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (metricsNode.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = metricsNode.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                String error = addMetric(metrics, field.getKey(), field.getValue());
                if (error != null) {
                    return new Invalid(sequence, "INVALID_METRIC_TYPE", error);
                }
            }
        }

        return new Valid(new TelemetryEvent(eventId, deviceId, device.tenantId(), boot.asLong(), sequence,
                deviceTime, receivedAt, type, metrics));
    }

    private static String addMetric(Map<String, Object> metrics, String name, JsonNode value) {
        double[] range = NUMERIC_METRICS.get(name);
        if (range != null) {
            if (!value.isNumber() || value.asDouble() < range[0] || value.asDouble() > range[1]) {
                return name + " must be a number between " + range[0] + " and " + range[1];
            }
            metrics.put(name, value.isIntegralNumber() ? (Object) value.asLong() : value.asDouble());
            return null;
        }
        Integer maxLength = TEXT_METRICS.get(name);
        if (maxLength != null) {
            if (!value.isTextual() || value.asText().length() > maxLength) {
                return name + " must be text of at most " + maxLength + " characters";
            }
            metrics.put(name, value.asText());
        }
        return null;
    }
}
