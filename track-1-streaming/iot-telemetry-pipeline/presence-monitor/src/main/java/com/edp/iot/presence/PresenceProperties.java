package com.edp.iot.presence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("iot.presence")
public record PresenceProperties(
        /* Only lines whose home region is this one are marked offline here (HLD section 7). */
        @DefaultValue("local") String region,
        @DefaultValue("1000") int batchSize,
        @DefaultValue("30s") Duration grace,
        /* Above this many unprocessed records the processor counts as behind and offline marking pauses. */
        @DefaultValue("50000") long maxLagRecords,
        /* An event-ingest beacon older than this counts as an instance that is down. */
        @DefaultValue("15s") Duration beaconMaxAge,
        @DefaultValue("state-processor") String processorGroup,
        @DefaultValue("30s") Duration registryCacheTtl,
        @DefaultValue("1h") Duration stateCacheTtl) {
}
