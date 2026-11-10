package com.edp.iot.state.store;

import com.edp.iot.common.Json;
import com.edp.iot.common.event.NetworkEvent;
import com.edp.iot.common.event.OrderingKey;
import com.edp.iot.common.line.Connection;
import com.edp.iot.common.line.LineState;
import com.edp.iot.common.state.DeviceState;
import com.edp.iot.state.model.SecurityEvent;
import com.edp.iot.state.model.UsageCycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL access for state-processor. Every write is safe to repeat (ADR-002). */
@Repository
public class StateStore {

    private static final String LOCK_LINES = """
            SELECT iccid, line_state FROM line WHERE iccid = ANY(?) ORDER BY iccid FOR UPDATE
            """;

    private static final String LOAD_STATES = """
            SELECT iccid, customer_id, connection, connection_reason, connection_since, last_seen, imei, region,
                   last_boot_id, last_sequence, last_network_time, metrics::text AS metrics
            FROM device_state WHERE iccid = ANY(?)
            """;

    private static final String UPSERT_STATE = """
            INSERT INTO device_state (iccid, customer_id, connection, connection_reason, connection_since, last_seen,
                                      imei, region, last_boot_id, last_sequence, last_network_time, metrics, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
            ON CONFLICT (iccid) DO UPDATE SET
                connection = EXCLUDED.connection,
                connection_reason = EXCLUDED.connection_reason,
                connection_since = EXCLUDED.connection_since,
                last_seen = GREATEST(device_state.last_seen, EXCLUDED.last_seen),
                imei = EXCLUDED.imei,
                region = EXCLUDED.region,
                last_boot_id = EXCLUDED.last_boot_id,
                last_sequence = EXCLUDED.last_sequence,
                last_network_time = EXCLUDED.last_network_time,
                metrics = EXCLUDED.metrics,
                updated_at = now()
            """;

    private static final String INSERT_USAGE = """
            INSERT INTO usage_record (event_id, iccid, cycle_start, bytes, network_time, received_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String LOCK_CYCLES = """
            SELECT iccid, cycle_start, bytes_used, allowance_bytes, notified_80, notified_100
            FROM usage_cycle WHERE iccid = ANY(?) AND cycle_start = ANY(?)
            ORDER BY iccid, cycle_start
            FOR UPDATE
            """;

    private static final String UPSERT_CYCLE = """
            INSERT INTO usage_cycle (iccid, cycle_start, bytes_used, allowance_bytes, notified_80, notified_100, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (iccid, cycle_start) DO UPDATE SET
                bytes_used = EXCLUDED.bytes_used,
                notified_80 = EXCLUDED.notified_80,
                notified_100 = EXCLUDED.notified_100,
                updated_at = now()
            """;

    private static final String INSERT_SECURITY = """
            INSERT INTO security_event (security_event_id, iccid, customer_id, sim_type, old_imei, new_imei, severity,
                                        auto_suspended, event_id, at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public StateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Row-locks the lines in a stable order (no deadlocks between processor instances) and
     * returns their current line state, which may be newer than the registry cache.
     */
    public Map<String, LineState> lockLines(Collection<String> iccids) {
        Map<String, LineState> result = new HashMap<>();
        if (iccids.isEmpty()) {
            return result;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(LOCK_LINES);
            ps.setArray(1, con.createArrayOf("text", iccids.toArray()));
            return ps;
        }, (ResultSet rs) -> {
            result.put(rs.getString("iccid"), LineState.valueOf(rs.getString("line_state")));
        });
        return result;
    }

    /** Device state of the given lines. Call after {@link #lockLines}, which serialises writers. */
    public Map<String, DeviceState> loadStates(Collection<String> iccids) {
        Map<String, DeviceState> result = new HashMap<>();
        if (iccids.isEmpty()) {
            return result;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(LOAD_STATES);
            ps.setArray(1, con.createArrayOf("text", iccids.toArray()));
            return ps;
        }, (ResultSet rs) -> {
            DeviceState s = mapState(rs);
            result.put(s.iccid(), s);
        });
        return result;
    }

    public static DeviceState mapState(ResultSet rs) throws SQLException {
        Long boot = (Long) rs.getObject("last_boot_id");
        Long seq = (Long) rs.getObject("last_sequence");
        Timestamp network = rs.getTimestamp("last_network_time");
        return new DeviceState(
                rs.getString("iccid"),
                rs.getString("customer_id"),
                Connection.valueOf(rs.getString("connection")),
                rs.getString("connection_reason"),
                rs.getTimestamp("connection_since").toInstant(),
                rs.getTimestamp("last_seen").toInstant(),
                rs.getString("imei"),
                rs.getString("region"),
                boot == null || seq == null ? null : new OrderingKey(boot, seq),
                network == null ? null : network.toInstant(),
                Json.readMap(rs.getString("metrics")));
    }

    public void upsertStates(Collection<DeviceState> states) {
        if (states.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(UPSERT_STATE, List.copyOf(states), states.size(), (ps, s) -> {
            ps.setString(1, s.iccid());
            ps.setString(2, s.customerId());
            ps.setString(3, s.connection().name());
            ps.setString(4, s.connectionReason());
            ps.setTimestamp(5, Timestamp.from(s.connectionSince()));
            ps.setTimestamp(6, Timestamp.from(s.lastSeen()));
            ps.setString(7, s.imei());
            ps.setString(8, s.region());
            if (s.deviceKey() == null) {
                ps.setNull(9, Types.BIGINT);
                ps.setNull(10, Types.BIGINT);
            } else {
                ps.setLong(9, s.deviceKey().bootId());
                ps.setLong(10, s.deviceKey().sequence());
            }
            ps.setTimestamp(11, s.lastNetworkTime() == null ? null : Timestamp.from(s.lastNetworkTime()));
            ps.setString(12, Json.write(s.metrics()));
        });
    }

    /**
     * Inserts usage records keyed by event ID and returns the IDs that were new. Only those are
     * added to the running total, so a record is counted once however often it arrives (ADR-006).
     */
    public Set<UUID> insertUsage(List<NetworkEvent> usage) {
        Set<UUID> inserted = new HashSet<>();
        if (usage.isEmpty()) {
            return inserted;
        }
        int[][] counts = jdbc.batchUpdate(INSERT_USAGE, usage, usage.size(), (ps, e) -> {
            ps.setObject(1, e.eventId());
            ps.setString(2, e.lineId());
            ps.setDate(3, Date.valueOf(UsageCycle.cycleOf(e.networkTime())));
            ps.setLong(4, e.usageBytes());
            ps.setTimestamp(5, Timestamp.from(e.networkTime()));
            ps.setTimestamp(6, Timestamp.from(e.receivedAt()));
        });
        int i = 0;
        for (int[] batch : counts) {
            for (int count : batch) {
                if (count > 0) {
                    inserted.add(usage.get(i).eventId());
                }
                i++;
            }
        }
        return inserted;
    }

    /** Locks and returns the usage cycles the batch touches, per line. */
    public Map<String, Map<LocalDate, UsageCycle>> lockCycles(Collection<String> iccids, Collection<LocalDate> cycleStarts) {
        Map<String, Map<LocalDate, UsageCycle>> result = new HashMap<>();
        if (iccids.isEmpty() || cycleStarts.isEmpty()) {
            return result;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(LOCK_CYCLES);
            ps.setArray(1, con.createArrayOf("text", iccids.toArray()));
            Array dates = con.createArrayOf("date", cycleStarts.stream().map(Date::valueOf).toArray());
            ps.setArray(2, dates);
            return ps;
        }, (ResultSet rs) -> {
            UsageCycle c = new UsageCycle(rs.getString("iccid"), rs.getDate("cycle_start").toLocalDate(),
                    rs.getLong("bytes_used"), rs.getLong("allowance_bytes"), rs.getBoolean("notified_80"),
                    rs.getBoolean("notified_100"));
            result.computeIfAbsent(c.iccid(), k -> new HashMap<>()).put(c.cycleStart(), c);
        });
        return result;
    }

    public void upsertCycles(Collection<UsageCycle> cycles) {
        if (cycles.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(UPSERT_CYCLE, List.copyOf(cycles), cycles.size(), (ps, c) -> {
            ps.setString(1, c.iccid());
            ps.setDate(2, Date.valueOf(c.cycleStart()));
            ps.setLong(3, c.bytesUsed());
            ps.setLong(4, c.allowanceBytes());
            ps.setBoolean(5, c.notified80());
            ps.setBoolean(6, c.notified100());
        });
    }

    /** Applies line-state changes made by the model (auto-suspend on a SIM move). */
    public void updateLineStates(Map<String, LineState> changes) {
        if (changes.isEmpty()) {
            return;
        }
        List<Map.Entry<String, LineState>> rows = new ArrayList<>(changes.entrySet());
        jdbc.batchUpdate("UPDATE line SET line_state = ?, line_state_since = now(), updated_at = now() WHERE iccid = ?",
                rows, rows.size(), (ps, e) -> {
                    ps.setString(1, e.getValue().name());
                    ps.setString(2, e.getKey());
                });
    }

    public void insertSecurityEvents(List<SecurityEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_SECURITY, events, events.size(), (ps, e) -> {
            ps.setObject(1, e.securityEventId());
            ps.setString(2, e.iccid());
            ps.setString(3, e.customerId());
            ps.setString(4, e.simType());
            ps.setString(5, e.oldImei());
            ps.setString(6, e.newImei());
            ps.setString(7, e.severity().name());
            ps.setBoolean(8, e.autoSuspended());
            ps.setObject(9, e.eventId());
            ps.setTimestamp(10, Timestamp.from(e.at()));
        });
    }

    /** Creates history partitions ahead of time and drops expired ones (V2 migration). */
    public void maintainPartitions(long historyDays) {
        jdbc.queryForList("SELECT iot_maintain_partitions(?)", (int) historyDays);
    }

    public static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
