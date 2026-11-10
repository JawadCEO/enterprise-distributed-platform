package com.edp.iot.presence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("iot.presence")
public record PresenceProperties(
        /* Only devices whose home region is this one are marked offline here (NFR, cross-region lag). */
        @DefaultValue("local") String region,
        @DefaultValue("1000") int batchSize,
        @DefaultValue("30s") Duration grace,
        /* Above this many unprocessed records the processor counts as behind and offline marking pauses. */
        @DefaultValue("50000") long maxLagRecords,
        /* A gateway beacon older than this counts as a gateway that is down. */
        @DefaultValue("15s") Duration beaconMaxAge,
        @DefaultValue("telemetry-processor") String processorGroup,
        @DefaultValue("30s") Duration registryCacheTtl,
        @DefaultValue("1h") Duration statusCacheTtl) {
}
