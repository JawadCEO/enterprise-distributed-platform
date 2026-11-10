package com.edp.iot.gateway;

import com.edp.iot.common.presence.PresenceKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Tells presence-monitor that ingestion is up. presence-monitor stops marking devices offline when
 * no gateway has reported recently (FR-20).
 */
@Component
class HealthBeacon {

    private static final Logger log = LoggerFactory.getLogger(HealthBeacon.class);

    private final StringRedisTemplate redis;
    private final String instanceId = "gw-" + UUID.randomUUID();

    HealthBeacon(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Scheduled(fixedRate = 5000)
    void beat() {
        try {
            redis.opsForZSet().add(PresenceKeys.GATEWAY_BEACONS, instanceId, System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.debug("Could not write gateway beacon: {}", e.toString());
        }
    }
}
