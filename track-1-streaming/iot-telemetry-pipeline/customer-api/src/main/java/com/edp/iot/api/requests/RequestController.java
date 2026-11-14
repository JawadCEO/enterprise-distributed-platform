package com.edp.iot.api.requests;

import com.edp.iot.api.CallerCustomer;
import com.edp.iot.common.request.LineRequest;
import com.edp.iot.common.request.RequestAction;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Line requests (FR-4 to FR-6, FR-10) and their status (FR-29). A profile switch is
 * {@code POST /v1/lines/{iccid}/profile} with {@code {"profile": "local-eu"}}. {@code POST /v1/lines/{iccid}/{action}}
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
    record RequestView(UUID requestId, String iccid, RequestAction action, String profile, UUID jobId, String status,
                       String reason, String previousState, Instant createdAt, Instant deadlineAt, Instant completedAt) {
        static RequestView of(LineRequest r) {
            return new RequestView(r.requestId(), r.iccid(), r.action(), r.profile(), r.jobId(), r.status().name(), r.reason(),
                    r.previousState().name(), r.createdAt(), r.deadlineAt(), r.completedAt());
        }
    }

    /** The body of {@code POST /v1/lines/{iccid}/profile}; other actions take none. */
    record ActionBody(String profile) {
    }

    @PostMapping("/v1/lines/{iccid}/{action}")
    ResponseEntity<RequestView> submit(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                                       @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                       @PathVariable String iccid, @PathVariable String action,
                                       @RequestBody(required = false) ActionBody body) {
        String customerId = CallerCustomer.require(customerHeader);
        RequestAction requested = RequestAction.fromPath(action)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new RequestRejected(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header of 1 to 128 characters", Map.of());
        }
        RequestService.Accepted accepted = service.submit(customerId, iccid, requested, idempotencyKey,
                body == null ? null : body.profile());
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
}
