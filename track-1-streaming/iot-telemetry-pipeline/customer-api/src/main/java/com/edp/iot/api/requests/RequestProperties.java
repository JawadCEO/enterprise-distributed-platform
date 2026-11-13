package com.edp.iot.api.requests;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for customer line requests ({@code iot.requests}).
 *
 * @param timeout          how long the network has to confirm a request before the sweeper fails it (FR-7)
 * @param ratePerSecond    requests each customer may send per second (FR-5)
 * @param publishTimeout   how long to wait for Kafka when publishing a command; the outbox relay retries after that
 * @param profiles         carrier profiles a line can be switched to (FR-10); a real plan would limit these per customer
 */
@ConfigurationProperties("iot.requests")
public record RequestProperties(
        @DefaultValue("120s") Duration timeout,
        @DefaultValue("20") int ratePerSecond,
        @DefaultValue("2s") Duration publishTimeout,
        @DefaultValue("12") int partitions,
        @DefaultValue("1") short replicationFactor,
        @DefaultValue("1") int minInsyncReplicas,
        @DefaultValue({"global-roaming", "local-eu", "local-us", "local-apac"}) java.util.List<String> profiles) {
}
