package com.edp.iot.processor.pipeline;

import com.edp.iot.common.Json;
import com.edp.iot.common.event.TelemetryEvent;
import com.edp.iot.common.presence.PresenceKeys;
import com.edp.iot.common.status.DeviceStatusView;
import com.edp.iot.common.status.StatusState;
import com.edp.iot.processor.ProcessorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis side of processing: duplicate keys (ADR-006), the status cache, and offline deadlines.
 *
 * <p>Redis is an accelerator here, never the source of truth. Every call degrades instead of
 * failing the batch: without duplicate keys a resend can add a second history row, without the
 * cache reads go to PostgreSQL, and a missed deadline update is corrected by the next event.
 */
@Component
public class RedisState {

    private static final Logger log = LoggerFactory.getLogger(RedisState.class);
    private static final String DEDUP_PREFIX = "dd:";

    private final StringRedisTemplate redis;
    private final ProcessorProperties props;

    public RedisState(StringRedisTemplate redis, ProcessorProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** Returns the dedup IDs of events already stored once. Empty if Redis is unavailable. */
    public Set<String> alreadySeen(List<TelemetryEvent> events) {
        if (events.isEmpty()) {
            return Set.of();
        }
        try {
            List<Object> exists = redis.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection c = (StringRedisConnection) connection;
                for (TelemetryEvent e : events) {
                    c.exists(DEDUP_PREFIX + e.dedupId());
                }
                return null;
            });
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < events.size(); i++) {
                if (Boolean.TRUE.equals(exists.get(i))) {
                    seen.add(events.get(i).dedupId());
                }
            }
            return seen;
        } catch (RuntimeException e) {
            log.warn("Duplicate check skipped, Redis unavailable: {}", e.toString());
            return Set.of();
        }
    }

    /**
     * After the database commit: remember stored events, refresh cached status, and push each
     * device's offline deadline forward. {@code ZADD GT} only ever moves a deadline later, so a
     * late or retried event cannot pull it back.
     */
    public void afterCommit(Collection<TelemetryEvent> stored, Collection<StatusState> statuses,
                            Map<String, Instant> deadlines) {
        try {
            long dedupSeconds = props.dedupTtl().toSeconds();
            long cacheSeconds = props.statusCacheTtl().toSeconds();
            byte[] deadlineKey = PresenceKeys.DEADLINES.getBytes(StandardCharsets.UTF_8);
            redis.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection c = (StringRedisConnection) connection;
                for (TelemetryEvent e : stored) {
                    c.stringCommands().set(bytes(DEDUP_PREFIX + e.dedupId()), bytes("1"),
                            Expiration.seconds(dedupSeconds), RedisStringCommands.SetOption.upsert());
                }
                for (StatusState s : statuses) {
                    c.setEx(DeviceStatusView.cacheKey(s.deviceId()), cacheSeconds, Json.write(DeviceStatusView.of(s)));
                }
                deadlines.forEach((deviceId, deadline) -> c.zSetCommands().zAdd(deadlineKey,
                        deadline.toEpochMilli(), bytes(deviceId), RedisZSetCommands.ZAddArgs.empty().gt()));
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Redis update after commit skipped: {}", e.toString());
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
