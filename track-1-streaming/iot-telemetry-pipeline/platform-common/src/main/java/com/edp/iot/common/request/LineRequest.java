package com.edp.iot.common.request;

import com.edp.iot.common.line.LineState;

import java.time.Instant;
import java.util.UUID;

/**
 * One customer request (a row of {@code line_request}).
 *
 * @param previousState the line state before the request; the line returns to it when the request fails
 * @param deadlineAt    after this the sweeper fails the request with {@code NETWORK_TIMEOUT} (FR-7)
 * @param profile       the target profile of a {@code SWITCH_PROFILE}, otherwise null
 * @param jobId         the bulk job the request belongs to (FR-9), or null for a single request
 */
public record LineRequest(
        UUID requestId,
        String customerId,
        String iccid,
        RequestAction action,
        RequestStatus status,
        String reason,
        LineState previousState,
        Instant createdAt,
        Instant deadlineAt,
        Instant completedAt,
        String profile,
        UUID jobId) {

    public LineRequest(UUID requestId, String customerId, String iccid, RequestAction action, RequestStatus status,
                       String reason, LineState previousState, Instant createdAt, Instant deadlineAt, Instant completedAt) {
        this(requestId, customerId, iccid, action, status, reason, previousState, createdAt, deadlineAt, completedAt, null,
                null);
    }
}
