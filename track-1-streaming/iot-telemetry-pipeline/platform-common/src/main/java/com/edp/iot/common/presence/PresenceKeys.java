package com.edp.iot.common.presence;

/** Redis keys shared by state-processor, presence-monitor and event-ingest. */
public final class PresenceKeys {

    /**
     * Sorted set of ICCIDs scored by their offline deadline in epoch milliseconds: last seen +
     * keep-alive window + grace. presence-monitor reads everything scored below now.
     */
    public static final String DEADLINES = "presence:deadline";

    /** Sorted set of event-ingest instance IDs scored by their last heartbeat in epoch milliseconds. */
    public static final String INGEST_BEACONS = "hb:event-ingest";

    /** Set of the regions lines have been seen in, for the outage guard. */
    public static final String REGIONS = "region:known";

    /** Width of one bucket of the per-region event counters. */
    public static final int REGION_BUCKET_SECONDS = 10;

    /** How long a region's event counter buckets are kept. */
    public static final int REGION_BUCKET_TTL_SECONDS = 3600;

    /**
     * Counter of events processed for lines last seen in {@code region}, for the
     * {@link #REGION_BUCKET_SECONDS}-wide bucket holding {@code epochSecond}. state-processor counts,
     * presence-monitor's outage guard compares the recent rate with the region's usual rate.
     */
    public static String regionBucket(String region, long epochSecond) {
        return "region:events:" + region + ":" + (epochSecond / REGION_BUCKET_SECONDS);
    }

    private PresenceKeys() {
    }
}
