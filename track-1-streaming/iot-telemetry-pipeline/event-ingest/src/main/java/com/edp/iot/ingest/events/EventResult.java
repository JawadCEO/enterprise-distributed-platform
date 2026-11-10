package com.edp.iot.ingest.events;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Result for one event in a batch (data.md section 7), identified by the sender's {@code eventId}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventResult(String eventId, String status, String code, Long retryAfterMs) {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    /** Not stored; the sender should resend it. */
    public static final String RETRY = "RETRY";

    public static EventResult accepted(String eventId) {
        return new EventResult(eventId, ACCEPTED, null, null);
    }

    public static EventResult rejected(String eventId, String code) {
        return new EventResult(eventId, REJECTED, code, null);
    }

    public static EventResult rateLimited(String eventId, long retryAfterMs) {
        return new EventResult(eventId, RATE_LIMITED, null, retryAfterMs);
    }

    public static EventResult retry(String eventId, long retryAfterMs) {
        return new EventResult(eventId, RETRY, "LOG_UNAVAILABLE", retryAfterMs);
    }
}
