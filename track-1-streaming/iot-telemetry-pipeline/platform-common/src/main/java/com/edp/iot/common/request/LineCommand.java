package com.edp.iot.common.request;

import java.time.Instant;
import java.util.UUID;

/**
 * What customer-api publishes on {@code line.commands}, keyed by ICCID, for provisioning-worker.
 *
 * @param profile the target profile of a {@code SWITCH_PROFILE}, otherwise null
 */
public record LineCommand(UUID requestId, String iccid, String customerId, RequestAction action, Instant deadlineAt,
                          String profile) {

    public LineCommand(UUID requestId, String iccid, String customerId, RequestAction action, Instant deadlineAt) {
        this(requestId, iccid, customerId, action, deadlineAt, null);
    }
}
