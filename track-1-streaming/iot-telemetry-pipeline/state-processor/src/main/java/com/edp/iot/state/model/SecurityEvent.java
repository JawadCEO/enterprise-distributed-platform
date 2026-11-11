package com.edp.iot.state.model;

import java.time.Instant;
import java.util.UUID;

/** A SIM seen in a different device, kept a year for security review ({@code security_event}). */
public record SecurityEvent(UUID securityEventId, String iccid, String customerId, String simType, String oldImei,
                            String newImei, Severity severity, boolean autoSuspended, UUID eventId, Instant at) {

    public enum Severity { MEDIUM, HIGH }
}
