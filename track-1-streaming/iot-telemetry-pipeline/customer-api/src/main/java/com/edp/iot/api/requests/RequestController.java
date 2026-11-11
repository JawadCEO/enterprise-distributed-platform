package com.edp.iot.api.requests;

import com.edp.iot.api.CallerCustomer;
import com.edp.iot.common.request.LineRequest;
import com.edp.iot.common.request.RequestAction;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Line requests (FR-4 to FR-6) and their status (FR-29). {@code POST /v1/lines/{iccid}/{action}}
 * answers {@code 202} with a {@code requestId}; the outcome arrives later (webhook in part 2c, or
 * {@code GET /v1/requests/{requestId}}).
 */
@RestController
class RequestController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RequestService service;

    RequestController(RequestService service) {
        this.service = service;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RequestView(UUID requestId, String iccid, RequestAction action, String status, String reason,
                       String previousState, Instant createdAt, Instant deadlineAt, Instant completedAt) {
        static RequestView of(LineRequest r) {
            return new RequestView(r.requestId(), r.iccid(), r.action(), r.status().name(), r.reason(),
                    r.previousState().name(), r.createdAt(), r.deadlineAt(), r.completedAt());
        }
    }

    @PostMapping("/v1/lines/{iccid}/{action}")
    ResponseEntity<RequestView> submit(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                                       @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                       @PathVariable String iccid, @PathVariable String action) {
        String customerId = CallerCustomer.require(customerHeader);
        RequestAction requested = RequestAction.fromPath(action)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new RequestRejected(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header of 1 to 128 characters", Map.of());
        }
        RequestService.Accepted accepted = service.submit(customerId, iccid, requested, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/v1/requests/" + accepted.request().requestId())
                .header("Idempotent-Replay", String.valueOf(accepted.replay()))
                .body(RequestView.of(accepted.request()));
    }

    @GetMapping("/v1/requests/{requestId}")
    RequestView get(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                    @PathVariable UUID requestId) {
        String customerId = CallerCustomer.require(customerHeader);
        return service.find(customerId, requestId).map(RequestView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @ExceptionHandler(RequestRejected.class)
    ResponseEntity<Map<String, Object>> onRejected(RequestRejected e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code);
        body.put("message", e.getMessage());
        body.putAll(e.details);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.status);
        if (e.details.get("retryAfterMs") instanceof Long ms) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (ms + 999) / 1000)));
        }
        return builder.body(body);
    }
}
