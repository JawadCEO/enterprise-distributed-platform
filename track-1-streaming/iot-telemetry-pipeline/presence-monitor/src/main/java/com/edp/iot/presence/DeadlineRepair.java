package com.edp.iot.presence;

import com.edp.iot.common.presence.PresenceKeys;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Puts back offline deadlines that Redis lost. The deadline set lives only in Redis, so after a
 * Redis restart (or while Redis was down and state-processor could not update it) a line that went
 * quiet before its next event would never be judged. Every few minutes this finds lines PostgreSQL
 * still has online but silent past their window, and adds their deadline if it is missing
 * ({@code ZADD NX}, so it never moves a deadline the processor set). The scanner then judges them as
 * usual, through the same guards.
 */
@Component
class DeadlineRepair {

    private static final Logger log = LoggerFactory.getLogger(DeadlineRepair.class);

    private static final String SILENT_ONLINE = """
            SELECT d.iccid,
                   (extract(epoch FROM d.last_seen) * 1000)::bigint
                       + COALESCE(l.keep_alive_seconds, k.keep_alive_seconds) * 1000::bigint + ? AS deadline_ms
            FROM device_state d
            JOIN line l ON l.iccid = d.iccid
            JOIN device_kind k ON k.device_kind = l.device_kind
            WHERE d.connection = 'ONLINE'
              AND l.line_state <> 'DEACTIVATED'
              AND l.home_region = ?
              AND d.last_seen + make_interval(secs => COALESCE(l.keep_alive_seconds, k.keep_alive_seconds)) < now()
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final PresenceProperties props;
    private final MeterRegistry meters;

    DeadlineRepair(JdbcTemplate jdbc, StringRedisTemplate redis, PresenceProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.props = props;
        this.meters = meters;
    }

    @Scheduled(initialDelayString = "${iot.presence.repair-interval-ms:120000}",
            fixedDelayString = "${iot.presence.repair-interval-ms:120000}")
    void repair() {
        try {
            Set<ZSetOperations.TypedTuple<String>> missing = new HashSet<>();
            jdbc.query(SILENT_ONLINE, rs -> {
                missing.add(new DefaultTypedTuple<>(rs.getString(1), (double) rs.getLong(2)));
            }, props.grace().toMillis(), props.region(), props.repairBatchSize());
            if (missing.isEmpty()) {
                return;
            }
            Long added = redis.opsForZSet().addIfAbsent(PresenceKeys.DEADLINES, missing);
            if (added != null && added > 0) {
                meters.counter("iot.presence.deadlines.repaired").increment(added);
                log.info("Put back {} offline deadlines missing from Redis", added);
            }
        } catch (RuntimeException e) {
            log.warn("Deadline repair failed: {}", e.toString());
        }
    }
}
