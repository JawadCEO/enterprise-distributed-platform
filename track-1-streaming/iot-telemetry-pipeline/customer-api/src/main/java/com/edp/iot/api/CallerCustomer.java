package com.edp.iot.api;

import com.edp.iot.common.security.ApiKeys;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the customer a request acts for (FR-30). A customer key always acts for its own
 * customer; an operator key names the customer in {@code X-Customer-Id}. Network keys can't call
 * this API. The caller was authenticated by {@link SecurityConfig}.
 */
public final class CallerCustomer {

    public static final String HEADER = "X-Customer-Id";
    static final String PRINCIPAL_ATTRIBUTE = "iot.principal";

    public static String require(String header) {
        ApiKeys.Principal principal = principal();
        if (principal.isOperator()) {
            if (header == null || header.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Operators must name the customer in " + HEADER);
            }
            return header;
        }
        if (header != null && !header.isBlank() && !header.equals(principal.customerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Key does not belong to customer " + header);
        }
        return principal.customerId();
    }

    public static ApiKeys.Principal requireOperator() {
        ApiKeys.Principal principal = principal();
        if (!principal.isOperator()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Operator key required");
        }
        return principal;
    }

    public static ApiKeys.Principal principal() {
        Object p = RequestContextHolder.currentRequestAttributes().getAttribute(PRINCIPAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (p instanceof ApiKeys.Principal principal) {
            return principal;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private CallerCustomer() {
    }
}
