package com.edp.iot.common.deadletter;

import java.time.Instant;
import java.util.UUID;

/**
 * What the processor publishes to {@code telemetry.dlq}. {@code payload} is the original record
 * value, kept as text because a malformed record may not be valid JSON.
 */
public record DeadLetterEnvelope(
        UUID deadLetterId,
        String sourceTopic,
        String deviceId,
        String tenantId,
        String reasonCode,
        String reason,
        int attempts,
        Instant firstFailureAt,
        Instant lastFailureAt,
        String payload) {
}
