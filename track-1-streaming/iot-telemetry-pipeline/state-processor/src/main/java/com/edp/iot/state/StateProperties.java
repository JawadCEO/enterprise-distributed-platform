package com.edp.iot.state;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("iot.state")
public record StateProperties(
        @DefaultValue("90d") Duration historyRetention,
        /* ADR-006: duplicate keys live 48 h in Redis. */
        @DefaultValue("48h") Duration dedupTtl,
        @DefaultValue("30s") Duration presenceGrace,
        @DefaultValue("1h") Duration stateCacheTtl,
        @DefaultValue("30s") Duration registryCacheTtl,
        @DefaultValue("1000000") long registryCacheSize,
        @DefaultValue("5s") Duration publishTimeout,
        @DefaultValue("12") int partitions,
        @DefaultValue("1") short replicationFactor,
        @DefaultValue("1") int minInsyncReplicas) {
}
