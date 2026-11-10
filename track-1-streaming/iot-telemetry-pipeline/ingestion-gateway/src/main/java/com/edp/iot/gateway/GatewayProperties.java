package com.edp.iot.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("iot.gateway")
public record GatewayProperties(
        @DefaultValue("100") int maxBatchEvents,
        @DefaultValue("262144") int maxBodyBytes,
        @DefaultValue("4096") int maxMetricsBytes,
        @DefaultValue("24h") Duration maxFutureSkew,
        @DefaultValue("10") int deviceRateLimitPerSec,
        @DefaultValue("1") int instanceCountHint,
        @DefaultValue("20000") int maxInFlightEvents,
        @DefaultValue("2s") Duration publishTimeout,
        @DefaultValue("30s") Duration registryCacheTtl,
        @DefaultValue("1000000") long registryCacheSize,
        @DefaultValue("12") int partitions,
        @DefaultValue("1") short replicationFactor,
        @DefaultValue("1") int minInsyncReplicas) {
}
