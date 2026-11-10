package com.edp.iot.state.pipeline;

import com.edp.iot.common.Json;
import com.edp.iot.common.event.NetworkEvent;
import com.edp.iot.common.presence.PresenceKeys;
import com.edp.iot.common.state.DeviceState;
import com.edp.iot.common.state.DeviceStateView;
import com.edp.iot.state.StateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Redis side of processing: duplicate keys (ADR-006), the device-state cache, and offline
 * deadlines.
 *
 * <p>Redis is an accelerator here, never the source of truth. Every call degrades instead of
 * failing the batch: without duplicate keys a resend is still caught by the ordering rules and,
 * for usage, by the {@code usage_record} key; without the cache reads go to PostgreSQL; a missed
 * deadline update is corrected by the next event.
 *
 * <p>An event's key is written only after its batch commits. Writing it first ({@code SET NX}
 * before processing) would turn a crash between the two into a lost event.
 */
@Component
public class RedisState {

    private static final Logger log = LoggerFactory.getLogger(RedisState.class);
    private static final String DEDUP_PREFIX = "dd:";

    private final StringRedisTemplate redis;
    private final StateProperties props;

    public RedisState(StringRedisTemplate redis, StateProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** Returns the IDs of events already processed once. Empty if Redis is unavailable. */
    public Set<UUID> alreadySeen(List<NetworkEvent> events) {
        if (events.isEmpty()) {
            return Set.of();
        }
        try {
            List<Object> exists = redis.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection c = (StringRedisConnection) connection;
                for (NetworkEvent e : events) {
                    c.exists(DEDUP_PREFIX + e.eventId());
                }
                return null;
            });
            Set<UUID> seen = new HashSet<>();
            for (int i = 0; i < events.size(); i++) {
                if (Boolean.TRUE.equals(exists.get(i))) {
                    seen.add(events.get(i).eventId());
                }
            }
            return seen;
        } catch (RuntimeException e) {
            log.warn("Duplicate check skipped, Redis unavailable: {}", e.toString());
            return Set.of();
        }
    }

    /**
     * After the database commit: remember processed events, refresh cached device state, and push
     * each line's offline deadline forward. {@code ZADD GT} only ever moves a deadline later, so a
     * late or retried event cannot pull it back.
     */
    public void afterCommit(Collection<NetworkEvent> processed, Collection<DeviceState> states,
                            Map<String, Instant> deadlines) {
        try {
            long dedupSeconds = props.dedupTtl().toSeconds();
            long cacheSeconds = props.stateCacheTtl().toSeconds();
            byte[] deadlineKey = bytes(PresenceKeys.DEADLINES);
            redis.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection c = (StringRedisConnection) connection;
                for (NetworkEvent e : processed) {
                    c.stringCommands().set(bytes(DEDUP_PREFIX + e.eventId()), bytes("1"),
                            Expiration.seconds(dedupSeconds), RedisStringCommands.SetOption.upsert());
                }
                for (DeviceState s : states) {
                    c.setEx(DeviceStateView.cacheKey(s.iccid()), cacheSeconds, Json.write(DeviceStateView.of(s)));
                }
                deadlines.forEach((iccid, deadline) -> c.zSetCommands().zAdd(deadlineKey,
                        deadline.toEpochMilli(), bytes(iccid), RedisZSetCommands.ZAddArgs.empty().gt()));
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
