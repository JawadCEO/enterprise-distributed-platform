package com.edp.iot.presence;

import com.edp.iot.common.presence.PresenceKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads a region's event-rate counters, which state-processor keeps in 10-second buckets by
 * receive time. The bucket being filled now and the one before it are skipped, since events
 * received then may still be in processing.
 */
@Component
class RegionRates {

    /** Events per second over the recent window and over the baseline window just before it. */
    record Rates(double recentPerSec, double baselinePerSec, int baselineBucketsWithTraffic, int baselineBuckets) {
    }

    private static final int SKIP = 2;

    private final StringRedisTemplate redis;

    RegionRates(StringRedisTemplate redis) {
        this.redis = redis;
    }

    Set<String> regions() {
        Set<String> regions = redis.opsForSet().members(PresenceKeys.REGIONS);
        return regions == null ? Set.of() : regions;
    }

    Rates read(String region, Instant now, Duration recentWindow, Duration baselineWindow) {
        int width = PresenceKeys.REGION_BUCKET_SECONDS;
        int recent = Math.max(1, (int) (recentWindow.toSeconds() / width));
        int baseline = Math.max(1, (int) (baselineWindow.toSeconds() / width));
        long newest = now.getEpochSecond() / width - SKIP;
        List<String> keys = new ArrayList<>(recent + baseline);
        for (long b = newest - recent - baseline + 1; b <= newest; b++) {
            keys.add(PresenceKeys.regionBucket(region, b * width));
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        long baselineSum = 0;
        int withTraffic = 0;
        long recentSum = 0;
        for (int i = 0; i < keys.size(); i++) {
            String v = values == null ? null : values.get(i);
            long count = v == null ? 0 : Long.parseLong(v);
            if (i < baseline) {
                baselineSum += count;
                if (count > 0) {
                    withTraffic++;
                }
            } else {
                recentSum += count;
            }
        }
        return new Rates((double) recentSum / (recent * width), (double) baselineSum / (baseline * width), withTraffic, baseline);
    }
}
