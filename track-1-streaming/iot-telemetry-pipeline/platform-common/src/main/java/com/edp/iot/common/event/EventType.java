package com.edp.iot.common.event;

/**
 * The event types of FR-11. Device events come from the device itself and are ordered by
 * {@code (bootId, sequence)}; network events come from the carrier network and are ordered by the
 * network's own timestamp (ADR-005).
 */
public enum EventType {
    DEVICE_STATUS(true),
    KEEP_ALIVE(true),
    DEVICE_ATTACHED(false),
    DEVICE_DETACHED(false),
    USAGE_RECORD(false),
    IMEI_CHANGED(false);

    private final boolean deviceEvent;

    EventType(boolean deviceEvent) {
        this.deviceEvent = deviceEvent;
    }

    public boolean isDeviceEvent() {
        return deviceEvent;
    }
}
