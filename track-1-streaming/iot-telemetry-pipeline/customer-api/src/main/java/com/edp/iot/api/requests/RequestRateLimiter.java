package com.edp.iot.api.requests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Requests per customer per second, as a one-second fixed window in Redis shared by every
 * customer-api instance. If Redis is down the request is let through: a missing limit for a few
 * seconds is better than refusing every customer.
 */
@Component
class RequestRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RequestRateLimiter.class);

    private final StringRedisTemplate redis;
    private final RequestProperties props;

    RequestRateLimiter(StringRedisTemplate redis, RequestProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** Milliseconds until the next window when over the limit, or 0 when allowed. */
    long acquire(String customerId) {
        long second = Instant.now().getEpochSecond();
        String key = "rl:req:" + customerId + ":" + second;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofSeconds(2));
            }
            if (count != null && count > props.ratePerSecond()) {
                return Math.max(1, (second + 1) * 1000 - System.currentTimeMillis());
            }
        } catch (RuntimeException e) {
            log.debug("Request rate limiter unavailable, allowing: {}", e.toString());
        }
        return 0;
    }
}
