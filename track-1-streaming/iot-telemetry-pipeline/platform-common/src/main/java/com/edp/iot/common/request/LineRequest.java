package com.edp.iot.common.request;

import com.edp.iot.common.line.LineState;

import java.time.Instant;
import java.util.UUID;

/**
 * One customer request (a row of {@code line_request}).
 *
 * @param previousState the line state before the request; the line returns to it when the request fails
 * @param deadlineAt    after this the sweeper fails the request with {@code NETWORK_TIMEOUT} (FR-7)
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
        Instant completedAt) {
}
