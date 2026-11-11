package com.edp.iot.presence;

import com.edp.iot.common.Json;
import com.edp.iot.common.change.LineChange;
import com.edp.iot.common.change.LineChangeOutbox;
import com.edp.iot.common.line.LineInfo;
import com.edp.iot.common.line.LineRegistry;
import com.edp.iot.common.presence.PresenceKeys;
import com.edp.iot.common.state.DeviceStateView;
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
 * Every scan: if the guard allows it, read lines whose offline deadline has passed and mark each
 * one offline with reason {@code KEEP_ALIVE_MISSED} (FR-19). The change is always recorded, but the
 * customer is told only for an {@code ACTIVE} line (FR-20). Several instances can run at once; the
 * conditional update in PostgreSQL makes sure each change happens once.
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
    private final LineRegistry registry;
    private final LineChangeOutbox outbox;
    private final TransactionTemplate tx;
    private final PresenceProperties props;
    private final PresenceGuard guard = new PresenceGuard();
    private final OutageGuard outages;
    private final MeterRegistry meters;
    private final AtomicLong lagGauge;
    private final AtomicLong pausedGauge;

    private volatile long longestWindowSeconds = -1;
    private volatile long windowReadAt;

    PresenceScanner(StringRedisTemplate redis, IngestionHealth health, OfflineStore store, LineRegistry registry,
                    LineChangeOutbox outbox, TransactionTemplate tx, PresenceProperties props, MeterRegistry meters,
                    OutageGuard outages) {
        this.outages = outages;
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
                log.debug("Offline marking paused: ingest up={}, lag={}, healthy since={}",
                        snapshot.ingestUp(), snapshot.processorLag(), guard.healthySince());
                return;
            }
            OutageGuard.Decision decision = outages.evaluate(now);
            if (decision.fleetSilent()) {
                pausedGauge.set(1);
                return; // a silence this wide is not the devices' doing (FR-21); judge no line now
            }
            int marked = markDue(now, decision.covered());
            meters.counter("iot.presence.offline.marked").increment(marked);
            if (marked > 0) {
                log.info("Marked {} lines offline", marked);
            }
        } catch (RuntimeException e) {
            log.warn("Presence scan failed: {}", e.toString());
        }
    }

    private int markDue(Instant now, Set<String> outageRegions) {
        // judged only once the deadline is outageHold old, so the outage guard has seen the region first
        long cutoff = now.minus(props.outageHold()).toEpochMilli();
        Set<String> due = redis.opsForZSet().rangeByScore(PresenceKeys.DEADLINES, 0, cutoff, 0, props.batchSize());
        if (due == null || due.isEmpty()) {
            return 0;
        }
        List<LineChange> changes = new ArrayList<>();
        for (String iccid : due) {
            Optional<LineInfo> info = registry.find(iccid);
            if (info.isEmpty() || info.get().isDeactivated()) {
                removeIfStale(iccid, cutoff);
                continue;
            }
            if (!props.region().equals(info.get().homeRegion())) {
                continue; // only a line's home region marks it offline (HLD section 7)
            }
            Duration window = info.get().keepAliveWindow().plus(props.grace());
            Optional<OfflineStore.Marked> marked = tx.execute(status -> {
                Optional<OfflineStore.Marked> m = store.markOffline(iccid, window, now, outageRegions);
                m.ifPresent(x -> outbox.insert(List.of(x.change())));
                return m;
            });
            if (marked != null && marked.isPresent()) {
                changes.add(marked.get().change());
                redis.opsForValue().set(DeviceStateView.cacheKey(iccid),
                        Json.write(DeviceStateView.of(marked.get().state())), props.stateCacheTtl());
            }
            removeIfStale(iccid, cutoff);
        }
        outbox.publishCommitted(changes);
        return changes.size();
    }

    private void removeIfStale(String iccid, long cutoffMillis) {
        redis.execute(REMOVE_IF_STALE, List.of(PresenceKeys.DEADLINES), iccid, String.valueOf(cutoffMillis));
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
