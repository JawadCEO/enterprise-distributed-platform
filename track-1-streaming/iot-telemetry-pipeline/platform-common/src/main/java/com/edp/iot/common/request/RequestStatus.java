package com.edp.iot.common.request;

/** Every accepted request ends {@code COMPLETED} or {@code FAILED} with a reason (FR-8). */
public enum RequestStatus {
    PENDING,
    COMPLETED,
    FAILED
}
