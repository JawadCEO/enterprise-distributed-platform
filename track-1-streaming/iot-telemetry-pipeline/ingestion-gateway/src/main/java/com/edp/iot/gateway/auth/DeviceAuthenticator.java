package com.edp.iot.gateway.auth;

import com.edp.iot.common.registry.DeviceInfo;
import com.edp.iot.common.registry.DeviceRegistry;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Interim device authentication until Phase 4 decides the final method: the device sends its ID in
 * {@code X-Device-Id} and its secret as a bearer token, which is compared to the SHA-256 hash in the
 * registry. Device secrets are long random values, not passwords, so a fast hash is enough here.
 */
@Component
public class DeviceAuthenticator {

    public static final String DEVICE_HEADER = "X-Device-Id";

    private final DeviceRegistry registry;

    public DeviceAuthenticator(DeviceRegistry registry) {
        this.registry = registry;
    }

    public DeviceInfo authenticate(String deviceId, String authorization) {
        if (deviceId == null || deviceId.isBlank() || authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AuthException(AuthException.Kind.UNAUTHENTICATED, "Missing device ID or bearer token");
        }
        Optional<DeviceInfo> found = registry.find(deviceId);
        if (found.isEmpty()) {
            throw new AuthException(AuthException.Kind.FORBIDDEN, "UNKNOWN_DEVICE");
        }
        DeviceInfo device = found.get();
        String presented = sha256Hex(authorization.substring("Bearer ".length()).trim());
        if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.US_ASCII),
                device.credentialHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new AuthException(AuthException.Kind.UNAUTHENTICATED, "Bad credentials");
        }
        if (!device.active()) {
            throw new AuthException(AuthException.Kind.FORBIDDEN, "INACTIVE_DEVICE");
        }
        return device;
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
