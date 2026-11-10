package com.edp.iot.processor.pipeline;

import com.edp.iot.common.Json;
import com.edp.iot.common.event.OrderingKey;
import com.edp.iot.common.event.TelemetryEvent;
import com.edp.iot.common.status.DeviceStatus;
import com.edp.iot.common.status.StatusState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** PostgreSQL access for the processor. Every write is safe to repeat (ADR-002). */
@Repository
public class TelemetryStore {

    private static final String INSERT_TELEMETRY = """
            INSERT INTO telemetry (device_id, tenant_id, boot_id, sequence, received_at, device_time,
                                   event_type, event_id, metrics)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT DO NOTHING
            """;

    private static final String LOCK_STATUS = """
            SELECT device_id, tenant_id, status, last_boot_id, last_sequence, last_seen, status_since, metrics::text AS metrics
            FROM device_status
            WHERE device_id = ANY(?)
            ORDER BY device_id
            FOR UPDATE
            """;

    /** The WHERE clause is the ordering guard: an older or equal key never overwrites newer state. */
    private static final String UPSERT_STATUS = """
            INSERT INTO device_status (device_id, tenant_id, status, last_boot_id, last_sequence, last_seen,
                                       status_since, metrics, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
            ON CONFLICT (device_id) DO UPDATE SET
                status = EXCLUDED.status,
                last_boot_id = EXCLUDED.last_boot_id,
                last_sequence = EXCLUDED.last_sequence,
                last_seen = GREATEST(device_status.last_seen, EXCLUDED.last_seen),
                status_since = EXCLUDED.status_since,
                metrics = EXCLUDED.metrics,
                updated_at = now()
            WHERE (device_status.last_boot_id, device_status.last_sequence)
                < (EXCLUDED.last_boot_id, EXCLUDED.last_sequence)
            """;

    private final JdbcTemplate jdbc;

    public TelemetryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertTelemetry(List<TelemetryEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_TELEMETRY, events, events.size(), (ps, e) -> {
            ps.setString(1, e.deviceId());
            ps.setString(2, e.tenantId());
            ps.setLong(3, e.bootId());
            ps.setLong(4, e.sequence());
            ps.setTimestamp(5, Timestamp.from(e.receivedAt()));
            ps.setTimestamp(6, e.deviceTime() == null ? null : Timestamp.from(e.deviceTime()));
            ps.setString(7, e.type().name());
            ps.setObject(8, e.eventId());
            ps.setString(9, Json.write(e.metrics()));
        });
    }

    /** Loads and row-locks the current status of the given devices, in a stable order to avoid deadlocks. */
    public Map<String, StatusState> lockStatuses(Collection<String> deviceIds) {
        Map<String, StatusState> result = new HashMap<>();
        if (deviceIds.isEmpty()) {
            return result;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(LOCK_STATUS);
            Array array = con.createArrayOf("text", deviceIds.toArray());
            ps.setArray(1, array);
            return ps;
        }, rs -> {
            StatusState s = new StatusState(
                    rs.getString("device_id"),
                    rs.getString("tenant_id"),
                    DeviceStatus.valueOf(rs.getString("status")),
                    new OrderingKey(rs.getLong("last_boot_id"), rs.getLong("last_sequence")),
                    rs.getTimestamp("last_seen").toInstant(),
                    rs.getTimestamp("status_since").toInstant(),
                    Json.readMap(rs.getString("metrics")));
            result.put(s.deviceId(), s);
        });
        return result;
    }

    public void upsertStatuses(Collection<StatusState> states) {
        if (states.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(UPSERT_STATUS, List.copyOf(states), states.size(), (ps, s) -> {
            ps.setString(1, s.deviceId());
            ps.setString(2, s.tenantId());
            ps.setString(3, s.status().name());
            ps.setLong(4, s.key().bootId());
            ps.setLong(5, s.key().sequence());
            ps.setTimestamp(6, Timestamp.from(s.lastSeen()));
            ps.setTimestamp(7, Timestamp.from(s.statusSince()));
            ps.setString(8, Json.write(s.metrics()));
        });
    }

    /** Creates partitions ahead of time and drops expired ones (V2 migration). */
    public void maintainPartitions(long telemetryHours, long statusDays) {
        jdbc.queryForList("SELECT iot_maintain_partitions(?, ?)", (int) telemetryHours, (int) statusDays);
    }
}
