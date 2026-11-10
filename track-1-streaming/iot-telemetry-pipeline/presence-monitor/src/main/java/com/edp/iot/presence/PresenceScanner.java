package com.edp.iot.presence;

import com.edp.iot.common.Json;
import com.edp.iot.common.outbox.StatusChangeOutbox;
import com.edp.iot.common.presence.PresenceKeys;
import com.edp.iot.common.registry.DeviceInfo;
import com.edp.iot.common.registry.DeviceRegistry;
import com.edp.iot.common.status.DeviceStatusView;
import com.edp.iot.common.status.StatusChangeEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every scan: if the guard allows it, read devices whose offline deadline has passed and mark each
 * one offline (FR-13). Several instances can run at once; the conditional update in PostgreSQL
 * makes sure each change happens once.
 */
@Component
class PresenceScanner {

    private static final Logger log = LoggerFactory.getLogger(PresenceScanner.class);

    /** Removes a deadline only if the processor has not pushed it forward since it was read. */
    private static final RedisScript<Long> REMOVE_IF_STALE = RedisScript.of("""
            local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if score and tonumber(score) <= tonumber(ARGV[2]) then
              return redis.call('ZREM', KEYS[1], ARGV[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final IngestionHealth health;
    private final OfflineStore store;
    private final DeviceRegistry registry;
    private final StatusChangeOutbox outbox;
    private final TransactionTemplate tx;
    private final PresenceProperties props;
    private final PresenceGuard guard = new PresenceGuard();
    private final MeterRegistry meters;
    private final AtomicLong lagGauge;
    private final AtomicLong pausedGauge;

    private volatile long longestWindowSeconds = -1;
    private volatile long windowReadAt;

    PresenceScanner(StringRedisTemplate redis, IngestionHealth health, OfflineStore store, DeviceRegistry registry,
                    StatusChangeOutbox outbox, TransactionTemplate tx, PresenceProperties props, MeterRegistry meters) {
        this.meters = meters;
        this.lagGauge = meters.gauge("iot.presence.processor.lag", new AtomicLong());
        this.pausedGauge = meters.gauge("iot.presence.paused", new AtomicLong());
        this.redis = redis;
        this.health = health;
        this.store = store;
        this.registry = registry;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${iot.presence.scan-interval-ms:10000}")
    void scan() {
        try {
            Instant now = Instant.now();
            IngestionHealth.Snapshot snapshot = health.check();
            Duration required = Duration.ofSeconds(longestWindow()).plus(props.grace());
            lagGauge.set(snapshot.processorLag());
            boolean allowed = guard.allows(snapshot.healthy(props.maxLagRecords()), now, required);
            pausedGauge.set(allowed ? 0 : 1);
            if (!allowed) {
                log.debug("Offline marking paused: gateway up={}, lag={}, healthy since={}",
                        snapshot.gatewayUp(), snapshot.processorLag(), guard.healthySince());
                return;
            }
            int marked = markDue(now);
            meters.counter("iot.presence.offline.marked").increment(marked);
            if (marked > 0) {
                log.info("Marked {} devices offline", marked);
            }
        } catch (RuntimeException e) {
            log.warn("Presence scan failed: {}", e.toString());
        }
    }

    private int markDue(Instant now) {
        Set<String> due = redis.opsForZSet().rangeByScore(PresenceKeys.DEADLINES, 0, now.toEpochMilli(), 0, props.batchSize());
        if (due == null || due.isEmpty()) {
            return 0;
        }
        List<StatusChangeEvent> changes = new ArrayList<>();
        for (String deviceId : due) {
            Optional<DeviceInfo> info = registry.find(deviceId);
            if (info.isEmpty() || !info.get().active()) {
                removeIfStale(deviceId, now);
                continue;
            }
            if (!props.region().equals(info.get().homeRegion())) {
                continue;
            }
            Duration window = info.get().heartbeatWindow().plus(props.grace());
            Optional<OfflineStore.Marked> marked = tx.execute(status -> {
                Optional<OfflineStore.Marked> m = store.markOffline(deviceId, window, now);
                m.ifPresent(x -> outbox.insert(List.of(x.change())));
                return m;
            });
            if (marked != null && marked.isPresent()) {
                changes.add(marked.get().change());
                redis.opsForValue().set(DeviceStatusView.cacheKey(deviceId),
                        Json.write(DeviceStatusView.of(marked.get().state())), props.statusCacheTtl());
            }
            removeIfStale(deviceId, now);
        }
        outbox.publishCommitted(changes);
        return changes.size();
    }

    private void removeIfStale(String deviceId, Instant now) {
        redis.execute(REMOVE_IF_STALE, List.of(PresenceKeys.DEADLINES), deviceId, String.valueOf(now.toEpochMilli()));
    }

    private long longestWindow() {
        long nowMs = System.currentTimeMillis();
        if (longestWindowSeconds < 0 || nowMs - windowReadAt > 60_000) {
            longestWindowSeconds = store.longestWindowSeconds();
            windowReadAt = nowMs;
        }
        return longestWindowSeconds;
    }
}
