package com.edp.iot.common.notify;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * Which URLs a customer may point a webhook at (FR-22, security phase). Outside the local stack an
 * endpoint must be https and every address its host resolves to must be public, so a customer can't
 * use the notifier to reach internal services, the cloud metadata endpoint or anything else on the
 * platform's own network. customer-api checks when the endpoint is registered and the notifier checks
 * again before each call, because a host's addresses can change after registration.
 *
 * <p>The notifier's HTTP client resolves the host again when it connects, so a host that answers DNS
 * with a public address and then a private one within the same second could still slip through. An
 * egress proxy that applies the same rule at connect time closes that gap in production.
 */
public final class WebhookUrlPolicy {

    /** Resolves a host to its addresses; replaced in tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public static final Resolver DNS = InetAddress::getAllByName;

    private final boolean allowInsecure;
    private final Resolver resolver;

    /**
     * @param allowInsecure true only for the local stack, where the simulated customer endpoints are
     *                      plain http on the Docker network's private addresses
     */
    public WebhookUrlPolicy(boolean allowInsecure, Resolver resolver) {
        this.allowInsecure = allowInsecure;
        this.resolver = resolver;
    }

    public WebhookUrlPolicy(boolean allowInsecure) {
        this(allowInsecure, DNS);
    }

    /** Why a URL is refused. */
    public record Refusal(String code, String message) {
    }

    /** Empty when the URL may be used. */
    public Optional<Refusal> check(String url) {
        if (url == null || url.isBlank() || url.length() > 2048) {
            return refuse("INVALID_URL", "url is required, at most 2048 characters");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return refuse("INVALID_URL", "url is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !(allowInsecure && scheme.equals("http"))) {
            return refuse("INVALID_URL", "url must be an absolute https URL");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return refuse("INVALID_URL", "url must name a host");
        }
        if (uri.getRawUserInfo() != null) {
            return refuse("INVALID_URL", "url must not carry a user name or password");
        }
        if (uri.getRawFragment() != null) {
            return refuse("INVALID_URL", "url must not have a fragment");
        }
        if (allowInsecure) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            return refuse("UNRESOLVABLE_HOST", "url's host does not resolve: " + host);
        }
        if (addresses == null || addresses.length == 0) {
            return refuse("UNRESOLVABLE_HOST", "url's host does not resolve: " + host);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                return refuse("PRIVATE_ADDRESS", "url's host resolves to an address that is not public");
            }
        }
        return Optional.empty();
    }

    private static Optional<Refusal> refuse(String code, String message) {
        return Optional.of(new Refusal(code, message));
    }

    /** True for an address on the public internet: not loopback, private, link-local, shared, reserved or multicast. */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicV4(b);
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return false; // fc00::/7 unique local
            }
            if (first == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
                return false; // 2001:db8::/32 documentation
            }
            if (allZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                return isPublicV4(tail(b)); // ::ffff:a.b.c.d, IPv4-mapped
            }
            if (allZero(b, 0, 12)) {
                return false; // ::a.b.c.d, IPv4-compatible (deprecated) and ::
            }
            if ((b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
                    && allZero(b, 4, 12)) {
                return isPublicV4(tail(b)); // 64:ff9b::/96, NAT64
            }
            if (first == 0x20 && (b[1] & 0xff) == 0x02) {
                return isPublicV4(new byte[]{b[2], b[3], b[4], b[5]}); // 2002::/16, 6to4
            }
            return true;
        }
        return false;
    }

    private static boolean isPublicV4(byte[] b) {
        int a0 = b[0] & 0xff;
        int a1 = b[1] & 0xff;
        int a2 = b[2] & 0xff;
        return !(a0 == 0                                   // 0.0.0.0/8 "this network"
                || a0 == 10                                // 10/8
                || a0 == 127                               // loopback
                || (a0 == 100 && (a1 & 0xc0) == 64)        // 100.64/10 carrier-grade NAT
                || (a0 == 169 && a1 == 254)                // link-local, cloud metadata
                || (a0 == 172 && (a1 & 0xf0) == 16)        // 172.16/12
                || (a0 == 192 && a1 == 0 && a2 == 0)       // 192.0.0/24 protocol assignments
                || (a0 == 192 && a1 == 0 && a2 == 2)       // documentation
                || (a0 == 192 && a1 == 168)                // 192.168/16
                || (a0 == 198 && (a1 & 0xfe) == 18)        // 198.18/15 benchmarking
                || (a0 == 198 && a1 == 51 && a2 == 100)    // documentation
                || (a0 == 203 && a1 == 0 && a2 == 113)     // documentation
                || a0 >= 224);                             // multicast, reserved, broadcast
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] tail(byte[] b) {
        return new byte[]{b[12], b[13], b[14], b[15]};
    }
}
