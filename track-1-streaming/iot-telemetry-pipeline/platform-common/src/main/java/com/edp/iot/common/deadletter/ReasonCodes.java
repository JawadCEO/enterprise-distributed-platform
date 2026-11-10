package com.edp.iot.common.deadletter;

public final class ReasonCodes {

    /** The line was removed from the registry after event-ingest accepted the event. */
    public static final String UNKNOWN_LINE = "UNKNOWN_LINE";
    /** The line was deactivated after event-ingest accepted the event. */
    public static final String INACTIVE_LINE = "INACTIVE_LINE";
    public static final String MALFORMED_RECORD = "MALFORMED_RECORD";
    public static final String PROCESSING_ERROR = "PROCESSING_ERROR";
    public static final String RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";

    private ReasonCodes() {
    }
}
