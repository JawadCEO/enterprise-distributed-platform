package com.edp.iot.ingest.auth;

public class AuthException extends RuntimeException {

    public enum Kind {
        /** 401: credentials missing or wrong. */
        UNAUTHENTICATED,
        /** 403: the key is valid but not a network sender key. */
        FORBIDDEN
    }

    private final Kind kind;

    public AuthException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
