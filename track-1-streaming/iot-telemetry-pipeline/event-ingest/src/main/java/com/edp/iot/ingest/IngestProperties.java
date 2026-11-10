package com.edp.iot.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("iot.ingest")
public record IngestProperties(
        @DefaultValue("100") int maxBatchEvents,
        @DefaultValue("262144") int maxBodyBytes,
        @DefaultValue("4096") int maxPayloadBytes,
        @DefaultValue("24h") Duration maxFutureSkew,
        /* ADR-006: older than the Kafka retention, an event could pass the duplicate check unseen. */
        @DefaultValue("7d") Duration maxEventAge,
        @DefaultValue("10") int lineRateLimitPerSec,
        @DefaultValue("1") int instanceCountHint,
        @DefaultValue("20000") int maxInFlightEvents,
        @DefaultValue("2s") Duration publishTimeout,
        @DefaultValue("30s") Duration registryCacheTtl,
        @DefaultValue("1000000") long registryCacheSize,
        @DefaultValue("12") int partitions,
        @DefaultValue("1") short replicationFactor,
        @DefaultValue("1") int minInsyncReplicas) {
}
