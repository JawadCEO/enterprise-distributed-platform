package com.edp.iot.ingest.ratelimit;

import com.edp.iot.common.line.LineInfo;
import com.edp.iot.ingest.IngestProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fallback used while Redis is unreachable. Each instance enforces its share of the
 * customer limit ({@code limit / instanceCountHint}), so the fleet-wide total stays close to the
 * configured limit without coordination. The per-line limit is applied in full, since one line's
 * events usually reach one instance at a time.
 */
class LocalRateLimiter implements RateLimiter {

    private final IngestProperties props;
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofSeconds(5))
            .maximumSize(2_000_000)
            .build();

    LocalRateLimiter(IngestProperties props) {
        this.props = props;
    }

    @Override
    public synchronized int acquire(LineInfo line, int requested) {
        long second = System.currentTimeMillis() / 1000;
        Window lineWindow = windows.get("l:" + line.iccid(), k -> new Window());
        Window customerWindow = windows.get("c:" + line.customerId(), k -> new Window());
        int customerShare = Math.max(1, line.customerRateLimitPerSec() / Math.max(1, props.instanceCountHint()));
        long allowed = Math.min(requested, Math.min(
                lineWindow.remaining(second, props.lineRateLimitPerSec()),
                customerWindow.remaining(second, customerShare)));
        if (allowed > 0) {
            lineWindow.used.addAndGet(allowed);
            customerWindow.used.addAndGet(allowed);
        }
        return (int) allowed;
    }

    private static final class Window {
        private long second = -1;
        private final AtomicLong used = new AtomicLong();

        long remaining(long now, int limit) {
            if (now != second) {
                second = now;
                used.set(0);
            }
            return Math.max(0, limit - used.get());
        }
    }
}
