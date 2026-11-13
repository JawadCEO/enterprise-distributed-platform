package com.edp.iot.api.requests;

import com.edp.iot.common.change.ChangeKind;
import com.edp.iot.common.change.LineChange;
import com.edp.iot.common.change.LineChangeOutbox;
import com.edp.iot.common.line.LineState;
import com.edp.iot.common.line.SimType;
import com.edp.iot.common.request.LineCommand;
import com.edp.iot.common.request.LineRequest;
import com.edp.iot.common.request.RequestAction;
import com.edp.iot.common.request.RequestStatus;
import com.edp.iot.common.request.RequestStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Accepts a line request (data.md section 4, stages 1 and 2). The checks run in the order FR-5
 * fixes and stop at the first failure. Accepting writes the request, the line's {@code PENDING}
 * state, its history rows and the command in one transaction, then publishes the command.
 */
@Service
class RequestService {

    /** What the caller gets back; {@code replay} is true when an earlier request with the same key is returned. */
    record Accepted(LineRequest request, boolean replay, List<LineChange> history) {
        Accepted(LineRequest request, boolean replay) {
            this(request, replay, List.of());
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RequestRateLimiter rateLimiter;
    private final CommandOutbox commands;
    private final LineChangeOutbox changes;
    private final RequestProperties props;

    RequestService(JdbcTemplate jdbc, TransactionTemplate tx, RequestRateLimiter rateLimiter, CommandOutbox commands,
                   LineChangeOutbox changes, RequestProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.rateLimiter = rateLimiter;
        this.commands = commands;
        this.changes = changes;
        this.props = props;
    }

    Accepted submit(String customerId, String iccid, RequestAction action, String idempotencyKey, String profile) {
        // 1. the request itself is well formed
        checkProfile(action, profile);
        // 2. the line belongs to the caller (404 either way, FR-30)
        if (!ownsLine(customerId, iccid)) {
            throw new RequestRejected(HttpStatus.NOT_FOUND, "NOT_FOUND", "Line not found", Map.of());
        }
        // 3. rate limit
        long retryAfter = rateLimiter.acquire(customerId);
        if (retryAfter > 0) {
            throw new RequestRejected(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests",
                    Map.of("retryAfterMs", retryAfter));
        }
        return submitChecked(customerId, iccid, action, idempotencyKey, profile, null);
    }

    /**
     * One line of a bulk job (FR-9): the same checks and the same path as a single request, except
     * the per-second rate limit, which the job's own pacing replaces. The job's ID and the line make
     * the idempotency key, so a line submitted twice (the runner restarted) gives the same request.
     */
    Accepted submitForJob(String customerId, String iccid, RequestAction action, String profile, UUID jobId) {
        if (!ownsLine(customerId, iccid)) {
            throw new RequestRejected(HttpStatus.NOT_FOUND, "NOT_FOUND", "Line not found", Map.of());
        }
        return submitChecked(customerId, iccid, action, "job:" + jobId + ":" + iccid, profile, jobId);
    }

    private Accepted submitChecked(String customerId, String iccid, RequestAction action, String idempotencyKey,
                                   String profile, UUID jobId) {
        // 4. idempotency key: a repeat gets the original answer and changes nothing
        Optional<LineRequest> earlier = byKey(customerId, idempotencyKey);
        if (earlier.isPresent()) {
            return new Accepted(earlier.get(), true);
        }
        try {
            Accepted accepted = tx.execute(status -> accept(customerId, iccid, action, idempotencyKey, profile, jobId));
            commands.publishCommitted(command(accepted.request()));
            changes.publishCommitted(accepted.history());
            return accepted;
        } catch (DuplicateKeyException e) {
            // the same key arrived twice at once, or a second request on the line won the race
            Optional<LineRequest> winner = byKey(customerId, idempotencyKey);
            if (winner.isPresent()) {
                return new Accepted(winner.get(), true);
            }
            throw new RequestRejected(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "Another request is in progress on this line",
                    Map.of());
        }
    }

    /** A profile switch names a profile the platform offers; no other action takes one (FR-10). */
    void checkProfile(RequestAction action, String profile) {
        if (action == RequestAction.SWITCH_PROFILE) {
            if (profile == null || profile.isBlank()) {
                throw new RequestRejected(HttpStatus.BAD_REQUEST, "PROFILE_REQUIRED",
                        "Send the target profile: {\"profile\": \"...\"}", Map.of("profiles", props.profiles()));
            }
            if (!props.profiles().contains(profile)) {
                throw new RequestRejected(HttpStatus.BAD_REQUEST, "INVALID_PROFILE", "Unknown profile " + profile,
                        Map.of("profiles", props.profiles()));
            }
        } else if (profile != null) {
            throw new RequestRejected(HttpStatus.BAD_REQUEST, "UNEXPECTED_PROFILE", action + " does not take a profile",
                    Map.of());
        }
    }

    private static LineCommand command(LineRequest r) {
        return new LineCommand(r.requestId(), r.iccid(), r.customerId(), r.action(), r.deadlineAt(), r.profile());
    }

    private Accepted accept(String customerId, String iccid, RequestAction action, String idempotencyKey, String profile,
                            UUID jobId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT l.line_state, l.sim_type, l.profile, c.account_status FROM line l JOIN customer c USING (customer_id)
                WHERE l.iccid = ? FOR UPDATE OF l
                """, iccid);
        LineState lineState = LineState.valueOf((String) row.get("line_state"));
        // 5a. a profile switch needs an embedded or integrated SIM, and a profile it isn't already on (FR-10)
        if (action.needsProgrammableSim() && SimType.valueOf((String) row.get("sim_type")) == SimType.REMOVABLE) {
            throw new RequestRejected(HttpStatus.CONFLICT, "NOT_SUPPORTED_FOR_SIM_TYPE",
                    action + " needs an embedded or integrated SIM; this line has a removable one",
                    Map.of("simType", SimType.REMOVABLE.name()));
        }
        if (action == RequestAction.SWITCH_PROFILE && profile.equals(row.get("profile"))) {
            throw new RequestRejected(HttpStatus.CONFLICT, "PROFILE_ALREADY_ACTIVE", "The line already runs " + profile,
                    Map.of("profile", profile));
        }
        Optional<LineRequest> inProgress = jdbc.query("SELECT " + RequestStore.COLUMNS
                + " FROM line_request WHERE iccid = ? AND status = 'PENDING'", RequestStore.MAPPER, iccid).stream().findFirst();
        // 5. the line's settled state allows the action (a PENDING line is judged by the state it came from)
        LineState settled = inProgress.map(LineRequest::previousState).orElse(lineState);
        if (!action.allowedFrom(settled)) {
            throw new RequestRejected(HttpStatus.CONFLICT, "INVALID_STATE",
                    action + " is not allowed while the line is " + settled, Map.of("lineState", settled.name()));
        }
        // 6. no other request in progress
        if (inProgress.isPresent()) {
            throw new RequestRejected(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "Another request is in progress on this line",
                    Map.of("requestId", inProgress.get().requestId().toString()));
        }
        // 7. account in good standing
        if ("BLOCKED".equals(row.get("account_status"))) {
            throw new RequestRejected(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED", "The account is blocked", Map.of());
        }

        Instant now = Instant.now();
        LineRequest request = new LineRequest(UUID.randomUUID(), customerId, iccid, action, RequestStatus.PENDING, null,
                lineState, now, now.plus(props.timeout()), null, profile, jobId);
        jdbc.update("""
                INSERT INTO line_request (request_id, customer_id, iccid, action, status, previous_state, idempotency_key,
                                          created_at, deadline_at, profile, job_id)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
                """, request.requestId(), customerId, iccid, action.name(), lineState.name(), idempotencyKey,
                Timestamp.from(now), Timestamp.from(request.deadlineAt()), profile, jobId);
        jdbc.update("UPDATE line SET line_state = 'PENDING', line_state_since = ?, updated_at = now() WHERE iccid = ?",
                Timestamp.from(now), iccid);
        commands.insert(command(request));
        Map<String, Object> acceptedDetails = new java.util.LinkedHashMap<>();
        acceptedDetails.put("requestId", request.requestId().toString());
        acceptedDetails.put("action", action.name());
        if (profile != null) {
            acceptedDetails.put("profile", profile);
        }
        if (jobId != null) {
            acceptedDetails.put("jobId", jobId.toString());
        }
        List<LineChange> history = List.of(
                LineChange.of(iccid, customerId, ChangeKind.REQUEST_ACCEPTED, false, null, now, acceptedDetails),
                LineChange.of(iccid, customerId, ChangeKind.LINE_STATE_CHANGED, false, null, now,
                        Map.of("from", lineState.name(), "to", LineState.PENDING.name(),
                                "requestId", request.requestId().toString())));
        changes.insert(history);
        return new Accepted(request, false, history);
    }

    Optional<LineRequest> find(String customerId, UUID requestId) {
        return jdbc.query("SELECT " + RequestStore.COLUMNS + " FROM line_request WHERE request_id = ? AND customer_id = ?",
                RequestStore.MAPPER, requestId, customerId).stream().findFirst();
    }

    private Optional<LineRequest> byKey(String customerId, String idempotencyKey) {
        return jdbc.query("SELECT " + RequestStore.COLUMNS + " FROM line_request WHERE customer_id = ? AND idempotency_key = ?",
                RequestStore.MAPPER, customerId, idempotencyKey).stream().findFirst();
    }

    private boolean ownsLine(String customerId, String iccid) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM line WHERE iccid = ? AND customer_id = ?", Integer.class,
                iccid, customerId);
        return n != null && n > 0;
    }
}
