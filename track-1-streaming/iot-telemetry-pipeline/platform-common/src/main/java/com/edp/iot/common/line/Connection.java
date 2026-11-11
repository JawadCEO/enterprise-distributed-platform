package com.edp.iot.common.line;

public enum Connection {
    ONLINE,
    OFFLINE;

    /**
     * Reason on a line marked offline while its region was in an outage (FR-21). The customer got
     * one outage notice instead of an alert for the line, so its return is not alerted either.
     */
    public static final String REASON_REGION_OUTAGE = "REGION_OUTAGE";
}
