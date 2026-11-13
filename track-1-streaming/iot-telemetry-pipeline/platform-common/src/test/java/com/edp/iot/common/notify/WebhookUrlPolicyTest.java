package com.edp.iot.common.notify;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookUrlPolicyTest {

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal); // a literal, so no lookup
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final Map<String, String> HOSTS = Map.of(
            "hooks.example.com", "93.184.216.34",
            "internal.example.com", "10.0.0.5",
            "metadata.example.com", "169.254.169.254",
            "v6.example.com", "2606:2800:220:1:248:1893:25c8:1946");

    private final WebhookUrlPolicy strict = new WebhookUrlPolicy(false, host -> {
        String a = HOSTS.get(host);
        if (a != null) {
            return new InetAddress[]{ip(a)};
        }
        if (host.equals("mixed.example.com")) {
            return new InetAddress[]{ip("93.184.216.34"), ip("192.168.1.10")};
        }
        if (Character.isDigit(host.charAt(0)) || host.contains(":")) {
            return new InetAddress[]{ip(host)};
        }
        throw new UnknownHostException(host);
    });

    private String code(String url) {
        return strict.check(url).map(WebhookUrlPolicy.Refusal::code).orElse("OK");
    }

    @Test
    void acceptsHttpsToAPublicHost() {
        assertThat(code("https://hooks.example.com/iot/events")).isEqualTo("OK");
        assertThat(code("https://v6.example.com:8443/hook?x=1")).isEqualTo("OK");
        assertThat(code("https://93.184.216.34/hook")).isEqualTo("OK");
    }

    @Test
    void refusesPlainHttpAndOtherSchemes() {
        assertThat(code("http://hooks.example.com/hook")).isEqualTo("INVALID_URL");
        assertThat(code("ftp://hooks.example.com/hook")).isEqualTo("INVALID_URL");
        assertThat(code("file:///etc/passwd")).isEqualTo("INVALID_URL");
        assertThat(code("hooks.example.com/hook")).isEqualTo("INVALID_URL");
    }

    @Test
    void refusesPrivateLoopbackAndMetadataAddresses() {
        assertThat(code("https://internal.example.com/hook")).isEqualTo("PRIVATE_ADDRESS");
        assertThat(code("https://metadata.example.com/latest/meta-data")).isEqualTo("PRIVATE_ADDRESS");
        assertThat(code("https://127.0.0.1/hook")).isEqualTo("PRIVATE_ADDRESS");
        assertThat(code("https://100.64.0.1/hook")).isEqualTo("PRIVATE_ADDRESS");
        assertThat(code("https://[::1]/hook")).isEqualTo("PRIVATE_ADDRESS");
        assertThat(code("https://[fd00::1]/hook")).isEqualTo("PRIVATE_ADDRESS");
        assertThat(code("https://[::ffff:10.0.0.1]/hook")).isEqualTo("PRIVATE_ADDRESS");
        assertThat(code("https://[64:ff9b::a9fe:a9fe]/hook")).isEqualTo("PRIVATE_ADDRESS");
    }

    @Test
    void refusesAHostWhenAnyOfItsAddressesIsPrivate() {
        assertThat(code("https://mixed.example.com/hook")).isEqualTo("PRIVATE_ADDRESS");
    }

    @Test
    void refusesUnresolvableHostsAndCredentialsInTheUrl() {
        assertThat(code("https://nowhere.example.com/hook")).isEqualTo("UNRESOLVABLE_HOST");
        assertThat(code("https://user:pw@hooks.example.com/hook")).isEqualTo("INVALID_URL");
    }

    @Test
    void theLocalStackAllowsPlainHttpToPrivateAddresses() {
        WebhookUrlPolicy local = new WebhookUrlPolicy(true, host -> {
            throw new AssertionError("no lookup when insecure URLs are allowed");
        });
        assertThat(local.check("http://network-simulator:9000/hooks/customer-001")).isEmpty();
        assertThat(local.check("ftp://network-simulator/hook")).isPresent();
    }
}
