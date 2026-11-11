package com.edp.iot.common.request;

import java.time.Instant;
import java.util.UUID;

/** What customer-api publishes on {@code line.commands}, keyed by ICCID, for provisioning-worker. */
public record LineCommand(UUID requestId, String iccid, String customerId, RequestAction action, Instant deadlineAt) {
}
