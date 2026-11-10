package com.edp.iot.common;

import java.time.Duration;
import java.util.List;

public final class Topics {

    public static final String RAW = "telemetry.raw";
    public static final String RETRY_5S = "telemetry.retry.5s";
    public static final String RETRY_1M = "telemetry.retry.1m";
    public static final String RETRY_10M = "telemetry.retry.10m";
    public static final String DLQ = "telemetry.dlq";
    public static final String STATUS_CHANGED = "device.status-changed";

    /** Retry tiers in order, with the delay each one waits before reprocessing. */
    public static final List<RetryTier> RETRY_TIERS = List.of(
            new RetryTier(RETRY_5S, Duration.ofSeconds(5)),
            new RetryTier(RETRY_1M, Duration.ofMinutes(1)),
            new RetryTier(RETRY_10M, Duration.ofMinutes(10)));

    /** Kafka headers carried on retry and replayed records. */
    public static final String HEADER_ATTEMPT = "x-attempt";
    public static final String HEADER_RETRY_AT = "x-retry-at";
    public static final String HEADER_FIRST_FAILURE_AT = "x-first-failure-at";
    public static final String HEADER_LAST_ERROR = "x-last-error";
    public static final String HEADER_REPLAY = "x-replay";

    public record RetryTier(String topic, Duration delay) {
    }

    private Topics() {
    }
}
