package com.edp.iot.api.requests;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Turns a failed check into its status and a body with {@code code}, {@code message} and details, for requests and jobs alike. */
@RestControllerAdvice(assignableTypes = {RequestController.class, JobController.class})
class RequestErrors {

    @ExceptionHandler(RequestRejected.class)
    ResponseEntity<Map<String, Object>> onRejected(RequestRejected e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code);
        body.put("message", e.getMessage());
        body.putAll(e.details);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.status);
        if (e.details.get("retryAfterMs") instanceof Long ms) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (ms + 999) / 1000)));
        }
        return builder.body(body);
    }
}
