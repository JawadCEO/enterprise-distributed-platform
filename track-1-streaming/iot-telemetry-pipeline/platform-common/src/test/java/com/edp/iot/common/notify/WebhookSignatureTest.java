package com.edp.iot.common.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    @Test
    void signatureMatchesAKnownHmac() {
        // HMAC-SHA256("secret", "1700000000.{}"), computed independently with openssl
        assertThat(WebhookSignature.sign("secret", 1_700_000_000L, "{}"))
                .isEqualTo("v1=b8569b78799ff9e3cbff0fc2d63a33a2b57f3282abd07c37ae5e8e7d79a5f163");
    }

    @Test
    void anyChangeToBodyTimestampOrSecretFailsVerification() {
        String body = "{\"notificationId\":\"n-1\",\"type\":\"DEVICE_OFFLINE\"}";
        String signature = WebhookSignature.sign("whsec_a", 1_700_000_000L, body);

        assertThat(WebhookSignature.verify("whsec_a", 1_700_000_000L, body, signature)).isTrue();
        assertThat(WebhookSignature.verify("whsec_a", 1_700_000_000L, body.replace("OFFLINE", "ONLINE"), signature)).isFalse();
        assertThat(WebhookSignature.verify("whsec_a", 1_700_000_001L, body, signature)).isFalse();
        assertThat(WebhookSignature.verify("whsec_b", 1_700_000_000L, body, signature)).isFalse();
        assertThat(WebhookSignature.verify("whsec_a", 1_700_000_000L, body, null)).isFalse();
    }

    @Test
    void secretsAreLongAndDistinct() {
        String a = WebhookSignature.newSecret();
        assertThat(a).startsWith("whsec_").hasSizeGreaterThan(40);
        assertThat(WebhookSignature.newSecret()).isNotEqualTo(a);
    }
}
