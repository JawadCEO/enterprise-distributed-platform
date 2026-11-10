package com.edp.iot.common.security;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * API keys for the HTTP APIs used by tenants and operators (Phase 4). A key looks like
 * {@code iot_<keyId>_<secret>}: the key ID is stored in clear and is safe to log, the whole key
 * only as a SHA-256 hash. Lookups are cached briefly, so a revoked key stops working within the
 * cache TTL.
 */
public class ApiKeys {

    public enum Role { TENANT, OPERATOR }

    /** Who is calling. {@code tenantId} is null for operators. */
    public record Principal(String keyId, Role role, String tenantId) {
        public boolean isOperator() {
            return role == Role.OPERATOR;
        }
    }

    public record IssuedKey(String keyId, String key) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private record Stored(String hash, Role role, String tenantId) {
    }

    private final JdbcTemplate jdbc;
    private final LoadingCache<String, Optional<Stored>> cache;

    public ApiKeys(JdbcTemplate jdbc, Duration ttl) {
        this.jdbc = jdbc;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(100_000).build(this::load);
    }

    public Optional<Principal> authenticate(String presented) {
        if (presented == null) {
            return Optional.empty();
        }
        String[] parts = presented.split("_", 3);
        if (parts.length != 3 || !parts[0].equals("iot")) {
            return Optional.empty();
        }
        Optional<Stored> stored = cache.get(parts[1]);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        byte[] expected = stored.get().hash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = sha256Hex(presented).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            return Optional.empty();
        }
        return Optional.of(new Principal(parts[1], stored.get().role(), stored.get().tenantId()));
    }

    public IssuedKey issue(Role role, String tenantId) {
        String keyId = randomToken(9);
        String key = "iot_" + keyId + "_" + randomToken(24);
        insert(keyId, key, role, tenantId);
        return new IssuedKey(keyId, key);
    }

    /** Stores a key chosen elsewhere, such as the bootstrap operator key from the environment. */
    public void insertIfAbsent(String key, Role role, String tenantId) {
        String[] parts = key.split("_", 3);
        if (parts.length != 3 || !parts[0].equals("iot")) {
            throw new IllegalArgumentException("Key must look like iot_<keyId>_<secret>");
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM api_key WHERE key_id = ?", Integer.class, parts[1]);
        if (count == null || count == 0) {
            insert(parts[1], key, role, tenantId);
        }
    }

    public boolean revoke(String keyId) {
        cache.invalidate(keyId);
        return jdbc.update("UPDATE api_key SET revoked_at = now() WHERE key_id = ? AND revoked_at IS NULL", keyId) > 0;
    }

    private void insert(String keyId, String key, Role role, String tenantId) {
        jdbc.update("INSERT INTO api_key (key_id, key_hash, role, tenant_id) VALUES (?, ?, ?, ?)",
                keyId, sha256Hex(key), role.name(), tenantId);
    }

    private Optional<Stored> load(String keyId) {
        return jdbc.query("SELECT key_hash, role, tenant_id FROM api_key WHERE key_id = ? AND revoked_at IS NULL",
                (rs, n) -> new Stored(rs.getString(1), Role.valueOf(rs.getString(2)), rs.getString(3)), keyId)
                .stream().findFirst();
    }

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b).replace('_', 'x').replace('-', 'y');
    }

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
