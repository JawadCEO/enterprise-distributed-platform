package com.edp.iot.common.status;

public enum ChangeCause {
    /** First event from a device, or an event bringing it back from OFFLINE. */
    EVENT,
    /** A battery or signal threshold was crossed in either direction. */
    THRESHOLD,
    FAULT_REPORTED,
    FAULT_CLEARED,
    HEARTBEAT_MISSED,
    /** A higher boot counter arrived and brought the device back from OFFLINE. */
    REBOOT
}
