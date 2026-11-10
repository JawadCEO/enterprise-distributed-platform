package com.edp.iot.common;

import java.time.Duration;
import java.util.List;

/** Kafka topics (HLD section 4). Every topic is keyed by line ID (ICCID). */
public final class Topics {

    public static final String NETWORK_EVENTS = "network.events";
    public static final String RETRY_5S = "network.events.retry.5s";
    public static final String RETRY_1M = "network.events.retry.1m";
    public static final String RETRY_10M = "network.events.retry.10m";
    public static final String DLQ = "network.events.dlq";
    public static final String LINE_CHANGES = "line.changes";

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
