package com.edp.iot.common.request;

import com.edp.iot.common.change.ChangeKind;
import com.edp.iot.common.change.LineChange;
import com.edp.iot.common.line.LineState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finishing a request, shared by state-processor (the network's {@code PROVISIONING_RESULT}) and
 * provisioning-worker's sweeper (time limit). Both lock the line row first and the request row
 * second, the same order customer-api uses to accept a request, so they never deadlock.
 */
public class RequestStore {

    public static final String REASON_NETWORK_TIMEOUT = "NETWORK_TIMEOUT";
    public static final String REASON_NETWORK_REJECTED = "NETWORK_REJECTED";

    public static final RowMapper<LineRequest> MAPPER = (rs, n) -> new LineRequest(
            rs.getObject("request_id", UUID.class),
            rs.getString("customer_id"),
            rs.getString("iccid"),
            RequestAction.valueOf(rs.getString("action")),
            RequestStatus.valueOf(rs.getString("status")),
            rs.getString("reason"),
            LineState.valueOf(rs.getString("previous_state")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("deadline_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());

    public static final String COLUMNS =
            "request_id, customer_id, iccid, action, status, reason, previous_state, created_at, deadline_at, completed_at";

    /** The line state a request leaves behind, and the history rows that describe it. */
    public record Finished(LineRequest request, LineState lineState, List<LineChange> changes) {
    }

    private final JdbcTemplate jdbc;

    public RequestStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the still-pending requests among {@code requestIds}. Call after locking their lines. */
    public Map<UUID, LineRequest> lockPending(Collection<UUID> requestIds) {
        Map<UUID, LineRequest> result = new LinkedHashMap<>();
        if (requestIds.isEmpty()) {
            return result;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT " + COLUMNS + " FROM line_request WHERE request_id = ANY(?) "
                    + "AND status = 'PENDING' ORDER BY request_id FOR UPDATE");
            ps.setArray(1, con.createArrayOf("uuid", requestIds.toArray()));
            return ps;
        }, MAPPER).forEach(r -> result.put(r.requestId(), r));
        return result;
    }

    /** Line states, locked, in a stable order. */
    public Map<String, LineState> lockLines(Collection<String> iccids) {
        Map<String, LineState> result = new HashMap<>();
        if (iccids.isEmpty()) {
            return result;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(
                    "SELECT iccid, line_state FROM line WHERE iccid = ANY(?) ORDER BY iccid FOR UPDATE");
            ps.setArray(1, con.createArrayOf("text", iccids.toArray()));
            return ps;
        }, rs -> {
            result.put(rs.getString(1), LineState.valueOf(rs.getString(2)));
        });
        return result;
    }

    /**
     * Ends a locked, pending request. On success the line takes the action's target state; on
     * failure it goes back to where it was. The line is only moved while it is still {@code PENDING}.
     *
     * @param lineStateNow the line's current (locked) state
     * @param eventId      the {@code PROVISIONING_RESULT} that ended it, or null for the sweeper
     */
    public Finished finish(LineRequest r, LineState lineStateNow, RequestStatus status, String reason, UUID eventId,
                           Instant at) {
        jdbc.update("UPDATE line_request SET status = ?, reason = ?, completed_at = ? WHERE request_id = ? AND status = 'PENDING'",
                status.name(), reason, Timestamp.from(at), r.requestId());
        LineState next = status == RequestStatus.COMPLETED ? r.action().target() : r.previousState();
        List<LineChange> changes = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestId", r.requestId().toString());
        details.put("action", r.action().name());
        if (reason != null) {
            details.put("reason", reason);
        }
        LineState after = lineStateNow;
        if (lineStateNow == LineState.PENDING) {
            jdbc.update("UPDATE line SET line_state = ?, line_state_since = ?, updated_at = now() WHERE iccid = ?",
                    next.name(), Timestamp.from(at), r.iccid());
            after = next;
            changes.add(LineChange.of(r.iccid(), r.customerId(), ChangeKind.LINE_STATE_CHANGED, false, eventId, at,
                    Map.of("from", LineState.PENDING.name(), "to", next.name(), "requestId", r.requestId().toString())));
        }
        details.put("lineState", after.name());
        changes.add(LineChange.of(r.iccid(), r.customerId(),
                status == RequestStatus.COMPLETED ? ChangeKind.REQUEST_COMPLETED : ChangeKind.REQUEST_FAILED, true,
                eventId, at, details));
        return new Finished(r, after, changes);
    }

    /** Pending requests past their deadline, oldest first, without locks (the caller locks and rechecks). */
    public List<LineRequest> overdue(Instant now, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM line_request WHERE status = 'PENDING' AND deadline_at < ? "
                + "ORDER BY deadline_at LIMIT ?", MAPPER, Timestamp.from(now), limit);
    }

    public void markNetworkAccepted(UUID requestId, Instant at) {
        jdbc.update("UPDATE line_request SET network_accepted_at = ? WHERE request_id = ? AND network_accepted_at IS NULL",
                Timestamp.from(at), requestId);
    }

    public boolean isPending(UUID requestId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM line_request WHERE request_id = ? AND status = 'PENDING'",
                Integer.class, requestId);
        return n != null && n > 0;
    }
}
