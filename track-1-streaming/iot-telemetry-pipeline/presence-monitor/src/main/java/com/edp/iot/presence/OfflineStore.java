package com.edp.iot.presence;

import com.edp.iot.common.Json;
import com.edp.iot.common.event.OrderingKey;
import com.edp.iot.common.status.ChangeCause;
import com.edp.iot.common.status.DeviceStatus;
import com.edp.iot.common.status.StatusChangeEvent;
import com.edp.iot.common.status.StatusState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class OfflineStore {

    /**
     * Marks the device offline only if PostgreSQL agrees it has been silent: an event that arrived
     * after the Redis deadline was read leaves the row untouched. The CTE returns the status
     * before the change.
     */
    private static final String MARK_OFFLINE = """
            WITH prev AS (
                SELECT device_id, status FROM device_status WHERE device_id = ? FOR UPDATE
            )
            UPDATE device_status d
               SET status = 'OFFLINE', status_since = now(), updated_at = now()
              FROM prev
             WHERE d.device_id = prev.device_id
               AND d.status <> 'OFFLINE'
               AND d.last_seen <= ?
            RETURNING prev.status AS previous_status, d.tenant_id, d.last_boot_id, d.last_sequence,
                      d.last_seen, d.status_since, d.metrics::text AS metrics
            """;

    record Marked(StatusState state, StatusChangeEvent change) {
    }

    private final JdbcTemplate jdbc;

    OfflineStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Call inside a transaction; the caller writes the change to the outbox in the same one. */
    Optional<Marked> markOffline(String deviceId, Duration window, Instant now) {
        Instant silentSince = now.minus(window);
        List<Marked> rows = jdbc.query(MARK_OFFLINE, (rs, n) -> {
            Instant statusSince = rs.getTimestamp("status_since").toInstant();
            OrderingKey key = new OrderingKey(rs.getLong("last_boot_id"), rs.getLong("last_sequence"));
            StatusState state = new StatusState(deviceId, rs.getString("tenant_id"), DeviceStatus.OFFLINE, key,
                    rs.getTimestamp("last_seen").toInstant(), statusSince, Json.readMap(rs.getString("metrics")));
            StatusChangeEvent change = new StatusChangeEvent(UUID.randomUUID(), deviceId, state.tenantId(),
                    DeviceStatus.valueOf(rs.getString("previous_status")), DeviceStatus.OFFLINE,
                    ChangeCause.HEARTBEAT_MISSED, key.bootId(), key.sequence(), statusSince);
            return new Marked(state, change);
        }, deviceId, Timestamp.from(silentSince));
        return rows.stream().findFirst();
    }

    /** Longest effective heartbeat window among active devices, in seconds. */
    long longestWindowSeconds() {
        Long max = jdbc.queryForObject("""
                SELECT COALESCE(max(COALESCE(d.heartbeat_seconds, t.heartbeat_seconds)), 0)
                FROM device d JOIN device_type t ON t.device_type = d.device_type
                WHERE d.active
                """, Long.class);
        return max == null ? 0 : max;
    }
}
