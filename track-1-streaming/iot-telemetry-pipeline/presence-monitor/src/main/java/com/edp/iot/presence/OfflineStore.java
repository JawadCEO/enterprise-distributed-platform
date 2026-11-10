package com.edp.iot.presence;

import com.edp.iot.common.Json;
import com.edp.iot.common.change.ChangeKind;
import com.edp.iot.common.change.LineChange;
import com.edp.iot.common.event.OrderingKey;
import com.edp.iot.common.line.Connection;
import com.edp.iot.common.line.LineState;
import com.edp.iot.common.state.DeviceState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
class OfflineStore {

    static final String REASON = "KEEP_ALIVE_MISSED";

    /**
     * Marks the line offline only if PostgreSQL agrees it has been silent: an event that arrived
     * after the Redis deadline was read leaves the row untouched. The line row is locked first,
     * in the same order state-processor locks, so the two never deadlock.
     */
    private static final String MARK_OFFLINE = """
            WITH l AS (
                SELECT iccid, line_state FROM line WHERE iccid = ? FOR UPDATE
            )
            UPDATE device_state d
               SET connection = 'OFFLINE', connection_reason = ?, connection_since = ?, updated_at = now()
              FROM l
             WHERE d.iccid = l.iccid
               AND d.connection = 'ONLINE'
               AND d.last_seen <= ?
            RETURNING l.line_state, d.customer_id, d.connection_since, d.last_seen, d.imei, d.region,
                      d.last_boot_id, d.last_sequence, d.last_network_time, d.metrics::text AS metrics
            """;

    record Marked(DeviceState state, LineChange change) {
    }

    private final JdbcTemplate jdbc;

    OfflineStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Call inside a transaction; the caller writes the change to the outbox in the same one. */
    Optional<Marked> markOffline(String iccid, Duration window, Instant now) {
        Instant silentSince = now.minus(window);
        List<Marked> rows = jdbc.query(MARK_OFFLINE, (rs, n) -> {
            Long boot = (Long) rs.getObject("last_boot_id");
            Long seq = (Long) rs.getObject("last_sequence");
            Timestamp network = rs.getTimestamp("last_network_time");
            DeviceState state = new DeviceState(iccid, rs.getString("customer_id"), Connection.OFFLINE, REASON, now,
                    rs.getTimestamp("last_seen").toInstant(), rs.getString("imei"), rs.getString("region"),
                    boot == null || seq == null ? null : new OrderingKey(boot, seq),
                    network == null ? null : network.toInstant(), Json.readMap(rs.getString("metrics")));
            // FR-20: a suspended or deactivated line, or one mid profile switch, goes quiet without an alert.
            boolean notifyCustomer = LineState.valueOf(rs.getString("line_state")) == LineState.ACTIVE;
            LineChange change = LineChange.of(iccid, state.customerId(), ChangeKind.OFFLINE, notifyCustomer, null, now,
                    Map.of("reason", REASON, "lastSeen", state.lastSeen().toString()));
            return new Marked(state, change);
        }, iccid, REASON, Timestamp.from(now), Timestamp.from(silentSince));
        return rows.stream().findFirst();
    }

    /** Longest effective keep-alive window among lines that are not deactivated, in seconds. */
    long longestWindowSeconds() {
        Long max = jdbc.queryForObject("""
                SELECT COALESCE(max(COALESCE(l.keep_alive_seconds, k.keep_alive_seconds)), 0)
                FROM line l JOIN device_kind k ON k.device_kind = l.device_kind
                WHERE l.line_state <> 'DEACTIVATED'
                """, Long.class);
        return max == null ? 0 : max;
    }
}
