package com.edp.iot.processor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("iot.processor")
public record ProcessorProperties(
        @DefaultValue("48h") Duration telemetryRetention,
        @DefaultValue("90d") Duration statusChangeRetention,
        @DefaultValue("24h") Duration dedupTtl,
        @DefaultValue("30s") Duration presenceGrace,
        @DefaultValue("1h") Duration statusCacheTtl,
        @DefaultValue("30s") Duration registryCacheTtl,
        @DefaultValue("1000000") long registryCacheSize,
        @DefaultValue("5s") Duration publishTimeout,
        @DefaultValue("12") int partitions,
        @DefaultValue("1") short replicationFactor,
        @DefaultValue("1") int minInsyncReplicas) {
}
