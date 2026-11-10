package com.edp.iot.gateway.ratelimit;

import com.edp.iot.common.registry.DeviceInfo;
import com.edp.iot.gateway.GatewayProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fallback used while Redis is unreachable. Each instance enforces its share of the
 * tenant limit ({@code limit / instanceCountHint}), so the fleet-wide total stays close to the
 * configured limit without coordination. The per-device limit is applied in full, since one device
 * usually talks to one instance at a time.
 */
class LocalRateLimiter implements RateLimiter {

    private final GatewayProperties props;
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofSeconds(5))
            .maximumSize(2_000_000)
            .build();

    LocalRateLimiter(GatewayProperties props) {
        this.props = props;
    }

    @Override
    public synchronized int acquire(DeviceInfo device, int requested) {
        long second = System.currentTimeMillis() / 1000;
        Window deviceWindow = windows.get("d:" + device.deviceId(), k -> new Window());
        Window tenantWindow = windows.get("t:" + device.tenantId(), k -> new Window());
        int tenantShare = Math.max(1, device.tenantRateLimitPerSec() / Math.max(1, props.instanceCountHint()));
        long allowed = Math.min(requested, Math.min(
                deviceWindow.remaining(second, props.deviceRateLimitPerSec()),
                tenantWindow.remaining(second, tenantShare)));
        if (allowed > 0) {
            deviceWindow.used.addAndGet(allowed);
            tenantWindow.used.addAndGet(allowed);
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
