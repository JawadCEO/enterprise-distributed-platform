package com.edp.iot.common.deadletter;

public final class ReasonCodes {

    public static final String UNKNOWN_DEVICE = "UNKNOWN_DEVICE";
    public static final String INACTIVE_DEVICE = "INACTIVE_DEVICE";
    public static final String MALFORMED_RECORD = "MALFORMED_RECORD";
    public static final String PROCESSING_ERROR = "PROCESSING_ERROR";
    public static final String RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";

    private ReasonCodes() {
    }
}
