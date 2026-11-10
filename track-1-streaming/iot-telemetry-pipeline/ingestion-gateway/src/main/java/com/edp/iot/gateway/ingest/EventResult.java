package com.edp.iot.gateway.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Result for one event in a batch (data.md, section 5.1). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventResult(Long sequence, String status, String code, Long retryAfterMs) {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    /** Not stored; the device should resend it. */
    public static final String RETRY = "RETRY";

    public static EventResult accepted(long sequence) {
        return new EventResult(sequence, ACCEPTED, null, null);
    }

    public static EventResult rejected(Long sequence, String code) {
        return new EventResult(sequence, REJECTED, code, null);
    }

    public static EventResult rateLimited(long sequence, long retryAfterMs) {
        return new EventResult(sequence, RATE_LIMITED, null, retryAfterMs);
    }

    public static EventResult retry(long sequence, long retryAfterMs) {
        return new EventResult(sequence, RETRY, "LOG_UNAVAILABLE", retryAfterMs);
    }
}
