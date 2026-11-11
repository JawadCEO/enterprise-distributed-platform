package com.edp.iot.api.requests;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** A request that failed one of the FR-5 checks, with its own status and code. */
class RequestRejected extends RuntimeException {

    final HttpStatus status;
    final String code;
    final Map<String, Object> details;

    RequestRejected(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }
}
