package com.edp.iot.common.status;

/** Per device type limits below which a reporting device counts as DEGRADED. */
public record Thresholds(int batteryDegradedPct, int signalDegradedDbm) {

    public static final Thresholds DEFAULT = new Thresholds(15, -110);
}
