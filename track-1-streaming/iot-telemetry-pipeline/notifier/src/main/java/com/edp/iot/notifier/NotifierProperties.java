package com.edp.iot.notifier;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("iot.notifier")
public record NotifierProperties(
        /* FR-24: how long a notification is retried before it is parked. */
        @DefaultValue("24h") Duration retryFor,
        @DefaultValue("1s") Duration retryInitial,
        @DefaultValue("10m") Duration retryMax,
        /* Requests in flight at once per endpoint: a slow endpoint uses this many slots and no more (ADR-008). */
        @DefaultValue("4") int maxInFlightPerWebhook,
        /* Requests in flight at once across all endpoints, per instance. */
        @DefaultValue("256") int maxInFlight,
        @DefaultValue("5s") Duration callTimeout,
        /* A claimed notification is someone else's to retry once its lease runs out (an instance died mid-send). */
        @DefaultValue("30s") Duration lease,
        @DefaultValue("30d") Duration retention,
        @DefaultValue("10s") Duration webhookCacheTtl,
        @DefaultValue("12") int partitions,
        @DefaultValue("1") short replicationFactor,
        @DefaultValue("1") int minInsyncReplicas,
        /* Local stack only: plain http and private addresses for the simulated customers (WebhookUrlPolicy). */
        @DefaultValue("false") boolean allowInsecureUrls) {
}
