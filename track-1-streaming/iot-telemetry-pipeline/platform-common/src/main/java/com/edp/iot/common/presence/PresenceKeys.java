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

    private PresenceKeys() {
    }
}
