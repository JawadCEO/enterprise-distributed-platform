package com.edp.iot.common.presence;

/** Redis keys shared by the processor, presence-monitor and gateway. */
public final class PresenceKeys {

    /**
     * Sorted set of device IDs scored by their offline deadline in epoch milliseconds:
     * last seen + heartbeat window + grace. presence-monitor reads everything scored below now.
     */
    public static final String DEADLINES = "presence:deadline";

    /** Sorted set of gateway instance IDs scored by their last heartbeat in epoch milliseconds. */
    public static final String GATEWAY_BEACONS = "hb:gateway";

    private PresenceKeys() {
    }
}
