package com.edp.iot.api.status;

import com.edp.iot.api.CallerTenant;
import com.edp.iot.api.Cursor;
import com.edp.iot.common.Json;
import com.edp.iot.common.status.DeviceStatus;
import com.edp.iot.common.status.DeviceStatusView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read queries (FR-8), all scoped to the caller's tenant (FR-17). */
@RestController
class StatusController {

    private static final Logger log = LoggerFactory.getLogger(StatusController.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    StatusController(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    record Page<T>(List<T> items, String nextCursor) {
    }

    record TelemetryRow(long bootId, long sequence, Instant receivedAt, Instant deviceTime, String type,
                        Map<String, Object> metrics) {
    }

    record StatusChangeRow(String from, String to, String cause, long bootId, long sequence, Instant changedAt) {
    }

    record DeviceStatusRow(String deviceId, DeviceStatus status, Instant lastSeen, Instant statusSince) {
    }

    /** Redis first, PostgreSQL on a miss or when Redis is down (NFR-11). */
    @GetMapping("/v1/devices/{deviceId}/status")
    DeviceStatusView status(@RequestHeader(value = CallerTenant.HEADER, required = false) String tenantHeader,
                            @PathVariable String deviceId) {
        String tenantId = CallerTenant.require(tenantHeader);
        try {
            String cached = redis.opsForValue().get(DeviceStatusView.cacheKey(deviceId));
            if (cached != null) {
                DeviceStatusView view = Json.read(cached, DeviceStatusView.class);
                if (tenantId.equals(view.tenantId())) {
                    return view;
                }
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            log.debug("Status cache unavailable, reading PostgreSQL: {}", e.toString());
        }
        return jdbc.query("""
                        SELECT device_id, tenant_id, status, last_boot_id, last_sequence, last_seen, status_since,
                               metrics::text AS metrics
                        FROM device_status WHERE device_id = ? AND tenant_id = ?
                        """, (rs, n) -> new DeviceStatusView(rs.getString("device_id"), rs.getString("tenant_id"),
                        DeviceStatus.valueOf(rs.getString("status")), rs.getLong("last_boot_id"), rs.getLong("last_sequence"),
                        rs.getTimestamp("last_seen").toInstant(), rs.getTimestamp("status_since").toInstant(),
                        Json.readMap(rs.getString("metrics"))), deviceId, tenantId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Raw telemetry, newest first, within the 48-hour window. */
    @GetMapping("/v1/devices/{deviceId}/history")
    Page<TelemetryRow> history(@RequestHeader(value = CallerTenant.HEADER, required = false) String tenantHeader,
                               @PathVariable String deviceId,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                               @RequestParam(required = false) String cursor,
                               @RequestParam(defaultValue = "100") int limit) {
        requireOwnDevice(CallerTenant.require(tenantHeader), deviceId);
        int size = clamp(limit);
        Instant upper = to == null ? Instant.now().plusSeconds(60) : to;
        Instant lower = from == null ? Instant.now().minus(Duration.ofHours(48)) : from;
        List<Object> args = new ArrayList<>(List.of(deviceId, Timestamp.from(lower), Timestamp.from(upper)));
        String keyset = "";
        if (cursor != null) {
            String[] parts = Cursor.decode(cursor, 3);
            keyset = " AND (received_at, boot_id, sequence) < (?, ?, ?)";
            args.add(Timestamp.from(Instant.parse(parts[0])));
            args.add(Long.parseLong(parts[1]));
            args.add(Long.parseLong(parts[2]));
        }
        args.add(size);
        List<TelemetryRow> rows = jdbc.query("""
                SELECT boot_id, sequence, received_at, device_time, event_type, metrics::text AS metrics
                FROM telemetry
                WHERE device_id = ? AND received_at >= ? AND received_at < ?""" + keyset + """
                 ORDER BY received_at DESC, boot_id DESC, sequence DESC
                LIMIT ?
                """, (rs, n) -> new TelemetryRow(rs.getLong("boot_id"), rs.getLong("sequence"),
                rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("device_time") == null ? null : rs.getTimestamp("device_time").toInstant(),
                rs.getString("event_type"), Json.readMap(rs.getString("metrics"))), args.toArray());
        String next = rows.size() == size ? Cursor.encode(rows.get(size - 1).receivedAt(), rows.get(size - 1).bootId(),
                rows.get(size - 1).sequence()) : null;
        return new Page<>(rows, next);
    }

    /** Status changes, newest first, kept 90 days. */
    @GetMapping("/v1/devices/{deviceId}/status-changes")
    Page<StatusChangeRow> statusChanges(@RequestHeader(value = CallerTenant.HEADER, required = false) String tenantHeader,
                                        @PathVariable String deviceId,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(defaultValue = "100") int limit) {
        String tenantId = CallerTenant.require(tenantHeader);
        int size = clamp(limit);
        List<Object> args = new ArrayList<>(List.of(deviceId, tenantId));
        String keyset = "";
        if (cursor != null) {
            keyset = " AND changed_at < ?";
            args.add(Timestamp.from(Instant.parse(Cursor.decode(cursor, 1)[0])));
        }
        args.add(size);
        List<StatusChangeRow> rows = jdbc.query("""
                SELECT from_status, to_status, cause, boot_id, sequence, changed_at
                FROM status_change
                WHERE device_id = ? AND tenant_id = ?""" + keyset + """
                 ORDER BY changed_at DESC
                LIMIT ?
                """, (rs, n) -> new StatusChangeRow(rs.getString("from_status"), rs.getString("to_status"),
                rs.getString("cause"), rs.getLong("boot_id"), rs.getLong("sequence"),
                rs.getTimestamp("changed_at").toInstant()), args.toArray());
        String next = rows.size() == size ? Cursor.encode(rows.get(size - 1).changedAt()) : null;
        return new Page<>(rows, next);
    }

    /** Devices of the caller's tenant, optionally filtered by status, paged by device ID. */
    @GetMapping("/v1/devices")
    Page<DeviceStatusRow> devices(@RequestHeader(value = CallerTenant.HEADER, required = false) String tenantHeader,
                                  @RequestParam(required = false) DeviceStatus status,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(defaultValue = "100") int limit) {
        String tenantId = CallerTenant.require(tenantHeader);
        int size = clamp(limit);
        StringBuilder sql = new StringBuilder(
                "SELECT device_id, status, last_seen, status_since FROM device_status WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>(List.of(tenantId));
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        if (cursor != null) {
            sql.append(" AND device_id > ?");
            args.add(Cursor.decode(cursor, 1)[0]);
        }
        sql.append(" ORDER BY device_id LIMIT ?");
        args.add(size);
        List<DeviceStatusRow> rows = jdbc.query(sql.toString(), (rs, n) -> new DeviceStatusRow(rs.getString("device_id"),
                DeviceStatus.valueOf(rs.getString("status")), rs.getTimestamp("last_seen").toInstant(),
                rs.getTimestamp("status_since").toInstant()), args.toArray());
        String next = rows.size() == size ? Cursor.encode(rows.get(size - 1).deviceId()) : null;
        return new Page<>(rows, next);
    }

    private void requireOwnDevice(String tenantId, String deviceId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM device WHERE device_id = ? AND tenant_id = ?",
                Integer.class, deviceId, tenantId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 500);
    }
}
