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
        @DefaultValue("1h") Duration stateCacheTtl,
        /* A line past its deadline is marked offline only this much later, so the outage guard has
           seen a whole recent window of a region's traffic before any line in it is judged. */
        @DefaultValue("45s") Duration outageHold,
        /* FR-21: a region is in an outage when its event rate over the recent window falls below
           this share of its usual rate... */
        @DefaultValue("0.2") double outageDropShare,
        /* ...and the outage ends when the rate is back above this share of the rate before it. */
        @DefaultValue("0.6") double outageRecoverShare,
        @DefaultValue("20s") Duration outageRecentWindow,
        @DefaultValue("2m") Duration outageBaselineWindow,
        /* Regions quieter than this many events a second are too small to judge. */
        @DefaultValue("0.5") double outageMinRatePerSec,
        /* After an outage ends, lines that have not reconnected yet are still not alerted for this long. */
        @DefaultValue("2m") Duration outageSettle,
        @DefaultValue("12") int partitions,
        @DefaultValue("1") short replicationFactor,
        @DefaultValue("1") int minInsyncReplicas) {
}
