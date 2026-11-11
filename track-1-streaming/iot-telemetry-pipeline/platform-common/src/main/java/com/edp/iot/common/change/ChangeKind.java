package com.edp.iot.common.change;

/**
 * What happened to a line. Every change is stored in {@code line_history} (FR-27) and published on
 * {@code line.changes}; {@link LineChange#notifyCustomer()} says whether the customer should get a webhook.
 */
public enum ChangeKind {
    /** The network saw the device attach. History only; {@link #ONLINE} carries the notification. */
    ATTACHED,
    /** The network saw the device detach. History only; {@link #OFFLINE} carries the notification. */
    DETACHED,
    ONLINE,
    OFFLINE,
    /** Usage crossed 80% or 100% of the plan allowance this cycle (FR-23, FR-25). */
    USAGE_THRESHOLD,
    /** The SIM appeared in a different device (FR-18). */
    SIM_MOVED,
    LINE_STATE_CHANGED,
    FAULT_REPORTED,
    FAULT_CLEARED
}
