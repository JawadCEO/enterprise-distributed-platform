package com.edp.iot.ingest.events;

import com.edp.iot.common.event.EventType;
import com.edp.iot.common.event.NetworkEvent;
import com.edp.iot.ingest.IngestProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Checks one event's envelope and payload (FR-11, FR-13) and turns it into a {@link NetworkEvent}
 * with the receive time filled in. The customer is added later from the registry, never taken
 * from the payload. Unknown payload fields are dropped, not rejected, so senders can add fields
 * before the platform knows them.
 */
@Component
public class EventValidator {

    static final Pattern ICCID = Pattern.compile("[0-9]{18,22}");
    static final Pattern IMEI = Pattern.compile("[0-9]{14,16}");

    /** Known numeric status fields and their allowed ranges. */
    private static final Map<String, double[]> STATUS_NUMBERS = Map.of(
            NetworkEvent.BATTERY_PCT, new double[]{0, 100},
            NetworkEvent.SIGNAL_DBM, new double[]{-200, 0},
            NetworkEvent.TEMPERATURE_C, new double[]{-100, 200});
    private static final Map<String, Integer> STATUS_TEXT = Map.of(
            NetworkEvent.FIRMWARE, 32,
            NetworkEvent.FAULT_CODE, 32);
    private static final long MAX_USAGE_BYTES = 10L * 1024 * 1024 * 1024;

    private final IngestProperties props;

    public EventValidator(IngestProperties props) {
        this.props = props;
    }

    public sealed interface Outcome permits Valid, Invalid {
    }

    public record Valid(NetworkEvent event) implements Outcome {
    }

    public record Invalid(String eventId, String code, String message) implements Outcome {
    }

    public Outcome validate(JsonNode node, Instant receivedAt) {
        if (node == null || !node.isObject()) {
            return new Invalid(null, "NOT_AN_OBJECT", "Event must be a JSON object");
        }
        String rawId = node.path("eventId").asText(null);
        UUID eventId;
        try {
            eventId = UUID.fromString(rawId == null ? "" : rawId);
        } catch (IllegalArgumentException e) {
            return new Invalid(rawId, "INVALID_EVENT_ID", "eventId must be a UUID");
        }
        String id = eventId.toString();
        EventType type;
        try {
            type = EventType.valueOf(node.path("type").asText(""));
        } catch (IllegalArgumentException e) {
            return new Invalid(id, "INVALID_TYPE", "type is not a known event type");
        }
        String lineId = node.path("lineId").asText("");
        if (!ICCID.matcher(lineId).matches()) {
            return new Invalid(id, "INVALID_LINE_ID", "lineId must be an ICCID of 18 to 22 digits");
        }
        String imei = node.hasNonNull("imei") ? node.path("imei").asText() : null;
        boolean imeiRequired = type.isDeviceEvent() || type == EventType.IMEI_CHANGED || type == EventType.DEVICE_ATTACHED;
        if ((imei == null && imeiRequired) || (imei != null && !IMEI.matcher(imei).matches())) {
            return new Invalid(id, "INVALID_IMEI", "imei must be 14 to 16 digits");
        }

        Long bootId = null;
        Long sequence = null;
        Instant deviceTime = null;
        Instant networkTime = null;
        if (type.isDeviceEvent()) {
            JsonNode boot = node.path("bootId");
            JsonNode seq = node.path("sequence");
            if (!boot.isIntegralNumber() || boot.asLong() < 0) {
                return new Invalid(id, "INVALID_BOOT_ID", "bootId must be a non-negative integer");
            }
            if (!seq.isIntegralNumber() || seq.asLong() < 0) {
                return new Invalid(id, "INVALID_SEQUENCE", "sequence must be a non-negative integer");
            }
            bootId = boot.asLong();
            sequence = seq.asLong();
            deviceTime = instant(node, "deviceTime");
            if (deviceTime == null) {
                return new Invalid(id, "INVALID_DEVICE_TIME", "deviceTime must be an ISO-8601 instant");
            }
        } else {
            networkTime = instant(node, "networkTime");
            if (networkTime == null) {
                return new Invalid(id, "INVALID_NETWORK_TIME", "networkTime must be an ISO-8601 instant");
            }
        }
        Instant eventTime = deviceTime != null ? deviceTime : networkTime;
        if (eventTime.isAfter(receivedAt.plus(props.maxFutureSkew()))) {
            return new Invalid(id, "TIME_IN_FUTURE", "event time is too far in the future");
        }
        if (networkTime != null && networkTime.isBefore(receivedAt.minus(props.maxEventAge()))) {
            return new Invalid(id, "EVENT_TOO_OLD", "event is older than the replay window");
        }

        JsonNode payloadNode = node.path("payload");
        if (!payloadNode.isMissingNode() && !payloadNode.isNull() && !payloadNode.isObject()) {
            return new Invalid(id, "INVALID_PAYLOAD", "payload must be an object");
        }
        if (payloadNode.isObject() && payloadNode.toString().getBytes(StandardCharsets.UTF_8).length > props.maxPayloadBytes()) {
            return new Invalid(id, "PAYLOAD_TOO_LARGE", "payload exceeds " + props.maxPayloadBytes() + " bytes");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        String error = switch (type) {
            case DEVICE_STATUS -> status(payloadNode, payload);
            case USAGE_RECORD -> usage(payloadNode, payload);
            case DEVICE_ATTACHED -> text(payloadNode, NetworkEvent.REGION, 32, payload);
            case PROVISIONING_RESULT -> provisioning(payloadNode, payload);
            default -> null;
        };
        if (error != null) {
            return new Invalid(id, "INVALID_PAYLOAD_FIELD", error);
        }

        return new Valid(new NetworkEvent(eventId, type, lineId, imei, null, bootId, sequence, deviceTime, networkTime,
                receivedAt, payload));
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(node.path(field).asText(""));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String status(JsonNode payload, Map<String, Object> out) {
        for (Map.Entry<String, double[]> field : STATUS_NUMBERS.entrySet()) {
            JsonNode value = payload.path(field.getKey());
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            double[] range = field.getValue();
            if (!value.isNumber() || value.asDouble() < range[0] || value.asDouble() > range[1]) {
                return field.getKey() + " must be a number between " + range[0] + " and " + range[1];
            }
            out.put(field.getKey(), value.isIntegralNumber() ? (Object) value.asLong() : value.asDouble());
        }
        for (Map.Entry<String, Integer> field : STATUS_TEXT.entrySet()) {
            String error = text(payload, field.getKey(), field.getValue(), out);
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    private static String usage(JsonNode payload, Map<String, Object> out) {
        boolean any = false;
        for (String field : new String[]{NetworkEvent.BYTES_UP, NetworkEvent.BYTES_DOWN}) {
            JsonNode value = payload.path(field);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (!value.isIntegralNumber() || value.asLong() < 0 || value.asLong() > MAX_USAGE_BYTES) {
                return field + " must be a whole number of bytes between 0 and " + MAX_USAGE_BYTES;
            }
            out.put(field, value.asLong());
            any = true;
        }
        return any ? null : "a usage record needs bytesUp or bytesDown";
    }

    /** The network's answer to a line command: which request, {@code DONE} or {@code REJECTED}, and why. */
    private static String provisioning(JsonNode payload, Map<String, Object> out) {
        String requestId = payload.path(NetworkEvent.REQUEST_ID).asText("");
        try {
            out.put(NetworkEvent.REQUEST_ID, UUID.fromString(requestId).toString());
        } catch (IllegalArgumentException e) {
            return NetworkEvent.REQUEST_ID + " must be a UUID";
        }
        String outcome = payload.path(NetworkEvent.OUTCOME).asText("");
        if (!outcome.equals("DONE") && !outcome.equals("REJECTED")) {
            return NetworkEvent.OUTCOME + " must be DONE or REJECTED";
        }
        out.put(NetworkEvent.OUTCOME, outcome);
        return text(payload, NetworkEvent.REASON, 64, out);
    }

    private static String text(JsonNode payload, String field, int maxLength, Map<String, Object> out) {
        JsonNode value = payload.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().length() > maxLength) {
            return field + " must be text of at most " + maxLength + " characters";
        }
        out.put(field, value.asText());
        return null;
    }
}
