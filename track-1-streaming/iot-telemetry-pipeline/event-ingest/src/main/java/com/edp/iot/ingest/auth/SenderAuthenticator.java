package com.edp.iot.ingest.auth;

import com.edp.iot.common.security.ApiKeys;
import org.springframework.stereotype.Component;

/**
 * Network senders (network elements, the device application gateway, the simulator) authenticate
 * with an API key of role {@code NETWORK} in {@code Authorization: ApiKey <key>}. The final method
 * for real network elements (mTLS) is a Phase 4 decision; the key keeps the contract simple until then.
 */
@Component
public class SenderAuthenticator {

    private final ApiKeys keys;

    public SenderAuthenticator(ApiKeys keys) {
        this.keys = keys;
    }

    public ApiKeys.Principal authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("ApiKey ")) {
            throw new AuthException(AuthException.Kind.UNAUTHENTICATED, "Missing API key");
        }
        ApiKeys.Principal principal = keys.authenticate(authorization.substring("ApiKey ".length()).trim())
                .orElseThrow(() -> new AuthException(AuthException.Kind.UNAUTHENTICATED, "Bad credentials"));
        if (!principal.isNetwork()) {
            throw new AuthException(AuthException.Kind.FORBIDDEN, "NETWORK_KEY_REQUIRED");
        }
        return principal;
    }
}
