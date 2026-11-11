package com.edp.iot.common.notice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A notice about a customer's whole account rather than one line, published on
 * {@code customer.notices} keyed by customer. Delivery is at least once (outbox relay), so consumers
 * de-duplicate on {@code noticeId}.
 *
 * @param details kind-specific facts, such as {@code outageId}, {@code region}, {@code linesAffected}
 */
public record CustomerNotice(UUID noticeId, String customerId, Kind kind, Instant at, Map<String, Object> details) {

    public enum Kind {
        /** A region went silent; one notice per customer instead of one offline alert per line (FR-21). */
        OUTAGE_STARTED,
        /** The region's traffic came back. */
        OUTAGE_ENDED
    }

    public CustomerNotice {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
