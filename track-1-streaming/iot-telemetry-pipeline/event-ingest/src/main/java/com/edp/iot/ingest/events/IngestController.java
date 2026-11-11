package com.edp.iot.ingest.events;

import com.edp.iot.common.Json;
import com.edp.iot.ingest.IngestProperties;
import com.edp.iot.ingest.auth.AuthException;
import com.edp.iot.ingest.auth.SenderAuthenticator;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code POST /v1/events}: a batch of up to 100 events or 256 KB from a network sender (FR-11). */
@RestController
public class IngestController {

    private final SenderAuthenticator authenticator;
    private final IngestService ingest;
    private final IngestProperties props;

    public IngestController(SenderAuthenticator authenticator, IngestService ingest, IngestProperties props) {
        this.authenticator = authenticator;
        this.ingest = ingest;
        this.props = props;
    }

    @PostMapping(path = "/v1/events", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> postEvents(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody byte[] body) {

        authenticator.authenticate(authorization);

        if (body.length > props.maxBodyBytes()) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "BODY_TOO_LARGE", "Request body exceeds " + props.maxBodyBytes() + " bytes");
        }
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(body);
        } catch (IOException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Body is not valid JSON");
        }
        JsonNode events = root == null ? null : root.get("events");
        if (events == null || !events.isArray() || events.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "NO_EVENTS", "Body must contain a non-empty events array");
        }
        if (events.size() > props.maxBatchEvents()) {
            return error(HttpStatus.BAD_REQUEST, "BATCH_TOO_LARGE", "At most " + props.maxBatchEvents() + " events per request");
        }

        List<JsonNode> nodes = new ArrayList<>(events.size());
        events.forEach(nodes::add);
        IngestService.Response response = ingest.ingest(nodes);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status());
        if (response.retryAfterMs() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (response.retryAfterMs() + 999) / 1000)));
        }
        return builder.body(Map.of("results", response.results()));
    }

    /**
     * The database is needed only for a line or key not in the cache yet. When it cannot be reached,
     * the sender is told to retry soon rather than kept waiting.
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> onDatabase(DataAccessException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("code", "REGISTRY_UNAVAILABLE", "message", "Line registry unavailable, retry"));
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<Map<String, Object>> onAuth(AuthException e) {
        HttpStatus status = e.kind() == AuthException.Kind.FORBIDDEN ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
        String code = e.kind() == AuthException.Kind.FORBIDDEN ? e.getMessage() : "UNAUTHENTICATED";
        return error(status, code, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
