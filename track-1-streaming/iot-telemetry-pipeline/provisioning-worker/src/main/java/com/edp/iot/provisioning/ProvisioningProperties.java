package com.edp.iot.provisioning;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for provisioning-worker ({@code iot.provisioning}).
 *
 * @param networkUrl         base URL of the network's provisioning interface
 * @param networkRatePerSec  most calls per second this instance makes to the network (FR-9 pacing)
 * @param retryInitial       first back-off after a failed call; doubles up to {@code retryMax}
 * @param callTimeout        time limit for one call to the network
 * @param retention          how long finished requests are kept
 */
@ConfigurationProperties("iot.provisioning")
public record ProvisioningProperties(
        @DefaultValue("http://localhost:8090") String networkUrl,
        @DefaultValue("100") int networkRatePerSec,
        @DefaultValue("250ms") Duration retryInitial,
        @DefaultValue("10s") Duration retryMax,
        @DefaultValue("5s") Duration callTimeout,
        @DefaultValue("90d") Duration retention,
        @DefaultValue("2s") Duration publishTimeout,
        @DefaultValue("4") int dispatchThreads) {
}
