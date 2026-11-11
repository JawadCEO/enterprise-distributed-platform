package com.edp.iot.common.notify;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Webhook signing (NFR-18a). Each request carries {@code X-Webhook-Timestamp} (epoch seconds) and
 * {@code X-Webhook-Signature: v1=<hex HMAC-SHA256 of "timestamp.body" with the endpoint's secret>}.
 * The receiver recomputes it and rejects stale timestamps, so a captured request can't be replayed later.
 */
public final class WebhookSignature {

    public static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    public static final String ID_HEADER = "X-Notification-Id";

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String sign(String secret, long epochSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((epochSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static boolean verify(String secret, long epochSeconds, String body, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, epochSeconds, body).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    /** A new secret: 32 random bytes, URL-safe base64, prefixed so it is recognisable in logs and scanners. */
    public static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private WebhookSignature() {
    }
}
