package com.edp.iot.gateway.ratelimit;

import com.edp.iot.common.registry.DeviceInfo;
import com.edp.iot.gateway.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fixed one-second windows per device and per tenant, checked and taken in one Redis round trip
 * per request (not per event). Only the events that are allowed are counted, so a rejected burst
 * does not eat into the next window.
 *
 * <p>If Redis cannot be reached, the gateway falls back to a local limiter so that ingestion keeps
 * working, degraded rather than down (NFR disaster recovery table).
 *
 * <p>Both keys are used in one script, so on a Redis Cluster they would need a common hash tag;
 * that change belongs with the move to a cluster.
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final RedisScript<Long> SCRIPT = RedisScript.of("""
            local n  = tonumber(ARGV[1])
            local dl = tonumber(ARGV[2])
            local tl = tonumber(ARGV[3])
            local d  = tonumber(redis.call('GET', KEYS[1]) or '0')
            local t  = tonumber(redis.call('GET', KEYS[2]) or '0')
            local allowed = math.min(n, math.max(0, dl - d), math.max(0, tl - t))
            if allowed > 0 then
              redis.call('INCRBY', KEYS[1], allowed)
              redis.call('PEXPIRE', KEYS[1], 2000)
              redis.call('INCRBY', KEYS[2], allowed)
              redis.call('PEXPIRE', KEYS[2], 2000)
            end
            return allowed
            """, Long.class);

    private final StringRedisTemplate redis;
    private final GatewayProperties props;
    private final LocalRateLimiter fallback;

    public RedisRateLimiter(StringRedisTemplate redis, GatewayProperties props) {
        this.redis = redis;
        this.props = props;
        this.fallback = new LocalRateLimiter(props);
    }

    @Override
    public int acquire(DeviceInfo device, int requested) {
        long window = System.currentTimeMillis() / 1000;
        List<String> keys = List.of("rl:d:" + device.deviceId() + ":" + window, "rl:t:" + device.tenantId() + ":" + window);
        try {
            Long allowed = redis.execute(SCRIPT, keys, String.valueOf(requested),
                    String.valueOf(props.deviceRateLimitPerSec()), String.valueOf(device.tenantRateLimitPerSec()));
            return allowed == null ? 0 : allowed.intValue();
        } catch (RuntimeException e) {
            log.debug("Redis rate limiter unavailable, using local limits: {}", e.toString());
            return fallback.acquire(device, requested);
        }
    }
}
