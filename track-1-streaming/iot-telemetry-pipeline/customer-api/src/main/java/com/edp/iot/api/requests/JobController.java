package com.edp.iot.api.requests;

import com.edp.iot.api.CallerCustomer;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Bulk jobs (FR-9) and their status (FR-29).
 *
 * <pre>
 * POST /v1/jobs                      {"action": "SUSPEND", "iccids": [...]}  or  {"action": "SWITCH_PROFILE", "profile": "local-eu", "iccids": [...]}
 * GET  /v1/jobs/{jobId}              counts so far
 * GET  /v1/jobs/{jobId}/failures     failed lines and why, by ICCID
 * </pre>
 *
 * Errors use the same handler and codes as single requests (RequestController).
 */
@RestController
class JobController {

    private final JobService jobs;

    JobController(JobService jobs) {
        this.jobs = jobs;
    }

    record JobRequest(String action, String profile, List<String> iccids) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record JobView(UUID jobId, RequestAction action, String profile, String status, int total, int queued, int inProgress,
                   int succeeded, int failed, int rejected, java.time.Instant createdAt, java.time.Instant completedAt) {
        static JobView of(JobService.Job j) {
            return new JobView(j.jobId(), j.action(), j.profile(), j.status(), j.total(), j.queued(), j.inProgress(),
                    j.succeeded(), j.failed(), j.rejected(), j.createdAt(), j.completedAt());
        }
    }

    record FailurePage(List<JobService.Failure> items, String nextCursor) {
    }

    @PostMapping("/v1/jobs")
    ResponseEntity<JobView> create(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                                   @RequestHeader(value = RequestController.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                   @RequestBody JobRequest body) {
        String customerId = CallerCustomer.require(customerHeader);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new RequestRejected(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header of 1 to 128 characters", Map.of());
        }
        RequestAction action;
        try {
            action = RequestAction.valueOf(body.action() == null ? "" : body.action().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RequestRejected(HttpStatus.BAD_REQUEST, "INVALID_ACTION", "Unknown action " + body.action(),
                    Map.of("actions", List.of(RequestAction.values())));
        }
        JobService.Created created = jobs.create(customerId, action, body.profile(), body.iccids(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/v1/jobs/" + created.job().jobId())
                .header("Idempotent-Replay", String.valueOf(created.replay()))
                .body(JobView.of(created.job()));
    }

    @GetMapping("/v1/jobs/{jobId}")
    JobView get(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                @PathVariable UUID jobId) {
        return jobs.find(CallerCustomer.require(customerHeader), jobId).map(JobView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/v1/jobs/{jobId}/failures")
    FailurePage failures(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                         @PathVariable UUID jobId, @RequestParam(required = false) String cursor,
                         @RequestParam(defaultValue = "500") int limit) {
        String customerId = CallerCustomer.require(customerHeader);
        jobs.find(customerId, jobId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        int size = Math.max(1, Math.min(limit, 5000));
        List<JobService.Failure> items = jobs.failures(customerId, jobId, cursor, size);
        return new FailurePage(items, items.size() == size ? items.get(size - 1).iccid() : null);
    }
}
