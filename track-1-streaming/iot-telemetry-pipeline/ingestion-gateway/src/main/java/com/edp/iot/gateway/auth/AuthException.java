package com.edp.iot.gateway.auth;

public class AuthException extends RuntimeException {

    public enum Kind {
        /** 401: credentials missing or wrong. */
        UNAUTHENTICATED,
        /** 403: the device is unknown or deactivated (FR-21). */
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
