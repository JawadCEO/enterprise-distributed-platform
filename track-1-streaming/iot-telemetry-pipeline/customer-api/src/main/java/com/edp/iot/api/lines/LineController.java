package com.edp.iot.api.lines;

import com.edp.iot.api.CallerCustomer;
import com.edp.iot.api.Cursor;
import com.edp.iot.common.Json;
import com.edp.iot.common.line.Connection;
import com.edp.iot.common.line.LineState;
import com.edp.iot.common.line.SimType;
import com.edp.iot.common.state.DeviceStateView;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Line queries (FR-26 to FR-28). Every query is limited to the caller's own lines: a valid ICCID
 * that belongs to another customer answers 404, the same as one that does not exist (FR-30).
 */
@RestController
class LineController {

    private static final Logger log = LoggerFactory.getLogger(LineController.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    LineController(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    record Page<T>(List<T> items, String nextCursor) {
    }

    record Usage(LocalDate cycleStart, long bytesUsed, long allowanceBytes, double percentUsed) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LineStatus(String iccid, String customerId, SimType simType, String deviceKind, String planCode,
                      LineState lineState, Instant lineStateSince, DeviceStateView device, Usage usage) {
    }

    record LineRow(String iccid, SimType simType, String deviceKind, LineState lineState, Connection connection,
                   Instant lastSeen) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record HistoryRow(UUID changeId, String kind, boolean notifyCustomer, Instant at, Map<String, Object> details) {
    }

    /**
     * Current state of a line (FR-26): line state from the registry, device state from Redis with a
     * PostgreSQL fallback (NFR-11), and usage this cycle.
     */
    @GetMapping("/v1/lines/{iccid}")
    LineStatus line(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                    @PathVariable String iccid) {
        String customerId = CallerCustomer.require(customerHeader);
        LineStatus base = jdbc.query("""
                        SELECT iccid, customer_id, sim_type, device_kind, plan_code, line_state, line_state_since,
                               data_allowance_bytes
                        FROM line WHERE iccid = ? AND customer_id = ?
                        """, (rs, n) -> new LineStatus(rs.getString("iccid"), rs.getString("customer_id"),
                        SimType.valueOf(rs.getString("sim_type")), rs.getString("device_kind"), rs.getString("plan_code"),
                        LineState.valueOf(rs.getString("line_state")), rs.getTimestamp("line_state_since").toInstant(),
                        null, new Usage(currentCycle(), 0, rs.getLong("data_allowance_bytes"), 0)), iccid, customerId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new LineStatus(base.iccid(), base.customerId(), base.simType(), base.deviceKind(), base.planCode(),
                base.lineState(), base.lineStateSince(), deviceState(iccid), usage(iccid, base.usage()));
    }

    /** The caller's lines, filtered by line state, connection or SIM type, paged by ICCID (FR-28). */
    @GetMapping("/v1/lines")
    Page<LineRow> lines(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                        @RequestParam(required = false) LineState lineState,
                        @RequestParam(required = false) Connection connection,
                        @RequestParam(required = false) SimType simType,
                        @RequestParam(required = false) String cursor,
                        @RequestParam(defaultValue = "100") int limit) {
        String customerId = CallerCustomer.require(customerHeader);
        int size = clamp(limit);
        StringBuilder sql = new StringBuilder("""
                SELECT l.iccid, l.sim_type, l.device_kind, l.line_state, d.connection, d.last_seen
                FROM line l LEFT JOIN device_state d ON d.iccid = l.iccid
                WHERE l.customer_id = ?""");
        List<Object> args = new ArrayList<>(List.of(customerId));
        if (lineState != null) {
            sql.append(" AND l.line_state = ?");
            args.add(lineState.name());
        }
        if (connection != null) {
            sql.append(" AND d.connection = ?");
            args.add(connection.name());
        }
        if (simType != null) {
            sql.append(" AND l.sim_type = ?");
            args.add(simType.name());
        }
        if (cursor != null) {
            sql.append(" AND l.iccid > ?");
            args.add(Cursor.decode(cursor, 1)[0]);
        }
        sql.append(" ORDER BY l.iccid LIMIT ?");
        args.add(size);
        List<LineRow> rows = jdbc.query(sql.toString(), (rs, n) -> new LineRow(rs.getString("iccid"),
                SimType.valueOf(rs.getString("sim_type")), rs.getString("device_kind"),
                LineState.valueOf(rs.getString("line_state")),
                rs.getString("connection") == null ? null : Connection.valueOf(rs.getString("connection")),
                rs.getTimestamp("last_seen") == null ? null : rs.getTimestamp("last_seen").toInstant()), args.toArray());
        String next = rows.size() == size ? Cursor.encode(rows.get(size - 1).iccid()) : null;
        return new Page<>(rows, next);
    }

    /** History of a line, newest first, for up to 90 days (FR-27). */
    @GetMapping("/v1/lines/{iccid}/history")
    Page<HistoryRow> history(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                             @PathVariable String iccid,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                             @RequestParam(required = false) String cursor,
                             @RequestParam(defaultValue = "100") int limit) {
        requireOwnLine(CallerCustomer.require(customerHeader), iccid);
        int size = clamp(limit);
        Instant upper = to == null ? Instant.now().plusSeconds(60) : to;
        Instant lower = from == null ? Instant.now().minus(Duration.ofDays(90)) : from;
        List<Object> args = new ArrayList<>(List.of(iccid, Timestamp.from(lower), Timestamp.from(upper)));
        String keyset = "";
        if (cursor != null) {
            String[] parts = Cursor.decode(cursor, 2);
            keyset = " AND (at, change_id) < (?, ?)";
            args.add(Timestamp.from(Instant.parse(parts[0])));
            args.add(UUID.fromString(parts[1]));
        }
        args.add(size);
        List<HistoryRow> rows = jdbc.query("""
                SELECT change_id, kind, notify, at, details::text AS details
                FROM line_history
                WHERE iccid = ? AND at >= ? AND at < ?""" + keyset + """
                 ORDER BY at DESC, change_id DESC
                LIMIT ?
                """, (rs, n) -> new HistoryRow(rs.getObject("change_id", UUID.class), rs.getString("kind"),
                rs.getBoolean("notify"), rs.getTimestamp("at").toInstant(), Json.readMap(rs.getString("details"))),
                args.toArray());
        String next = rows.size() == size
                ? Cursor.encode(rows.get(size - 1).at(), rows.get(size - 1).changeId()) : null;
        return new Page<>(rows, next);
    }

    /** Usage per billing cycle, newest first, for up to 13 cycles. */
    @GetMapping("/v1/lines/{iccid}/usage")
    List<Usage> usageHistory(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                             @PathVariable String iccid) {
        requireOwnLine(CallerCustomer.require(customerHeader), iccid);
        return jdbc.query("""
                SELECT cycle_start, bytes_used, allowance_bytes FROM usage_cycle
                WHERE iccid = ? ORDER BY cycle_start DESC LIMIT 13
                """, (rs, n) -> usageOf(rs.getDate("cycle_start").toLocalDate(), rs.getLong("bytes_used"),
                rs.getLong("allowance_bytes")), iccid);
    }

    private DeviceStateView deviceState(String iccid) {
        try {
            String cached = redis.opsForValue().get(DeviceStateView.cacheKey(iccid));
            if (cached != null) {
                return Json.read(cached, DeviceStateView.class);
            }
        } catch (RuntimeException e) {
            log.debug("Device state cache unavailable, reading PostgreSQL: {}", e.toString());
        }
        return jdbc.query("""
                SELECT iccid, customer_id, connection, connection_reason, connection_since, last_seen, imei, region,
                       last_boot_id, last_sequence, metrics::text AS metrics
                FROM device_state WHERE iccid = ?
                """, (rs, n) -> new DeviceStateView(rs.getString("iccid"), rs.getString("customer_id"),
                Connection.valueOf(rs.getString("connection")), rs.getString("connection_reason"),
                rs.getTimestamp("connection_since").toInstant(), rs.getTimestamp("last_seen").toInstant(),
                rs.getString("imei"), rs.getString("region"), (Long) rs.getObject("last_boot_id"),
                (Long) rs.getObject("last_sequence"), Json.readMap(rs.getString("metrics"))), iccid)
                .stream().findFirst().orElse(null);
    }

    private Usage usage(String iccid, Usage empty) {
        return jdbc.query("SELECT bytes_used, allowance_bytes FROM usage_cycle WHERE iccid = ? AND cycle_start = ?",
                        (rs, n) -> usageOf(empty.cycleStart(), rs.getLong(1), rs.getLong(2)), iccid,
                        java.sql.Date.valueOf(empty.cycleStart()))
                .stream().findFirst().orElse(empty);
    }

    private static Usage usageOf(LocalDate cycleStart, long used, long allowance) {
        double pct = allowance <= 0 ? 0 : Math.round(used * 1000.0 / allowance) / 10.0;
        return new Usage(cycleStart, used, allowance, pct);
    }

    private static LocalDate currentCycle() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
    }

    private void requireOwnLine(String customerId, String iccid) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM line WHERE iccid = ? AND customer_id = ?",
                Integer.class, iccid, customerId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 500);
    }
}
