package com.edp.iot.api.registry;

import com.edp.iot.api.CallerTenant;
import com.edp.iot.common.security.ApiKeys;
import com.edp.iot.common.security.AuditLog;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Tenant and device registration (FR-14, FR-16). Changes reach the gateway and processor within
 * their registry cache TTL (30 seconds by default).
 */
@RestController
class RegistryController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_BATCH = 1000;

    private final JdbcTemplate jdbc;
    private final ApiKeys apiKeys;
    private final AuditLog audit;

    RegistryController(JdbcTemplate jdbc, ApiKeys apiKeys, AuditLog audit) {
        this.jdbc = jdbc;
        this.apiKeys = apiKeys;
        this.audit = audit;
    }

    record TenantRequest(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String tenantId,
                         @NotBlank @Size(max = 200) String name,
                         @Positive Integer rateLimitPerSec) {
    }

    record TenantUpdate(@Size(max = 200) String name, @Positive Integer rateLimitPerSec) {
    }

    record Tenant(String tenantId, String name, int rateLimitPerSec, Instant createdAt, Instant updatedAt) {
    }

    record DeviceRequest(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String deviceId,
                         @NotBlank String deviceType,
                         @Size(max = 32) String firmware,
                         @Positive Integer heartbeatSeconds,
                         @Size(max = 32) String homeRegion,
                         /* Optional: lets a provisioning tool or the simulator supply its own secret. */
                         @Size(min = 16, max = 256) String secret) {
    }

    record DeviceUpdate(@Size(max = 32) String firmware, @Positive Integer heartbeatSeconds, String deviceType) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Device(String deviceId, String tenantId, String deviceType, String firmware, Integer heartbeatSeconds,
                  String homeRegion, boolean active, Instant createdAt, Instant updatedAt, String secret) {
    }

    @PostMapping("/v1/tenants")
    ResponseEntity<Tenant> createTenant(@Valid @RequestBody TenantRequest request) {
        var actor = CallerTenant.requireOperator();
        jdbc.update("INSERT INTO tenant (tenant_id, name, rate_limit_per_sec) VALUES (?, ?, ?)",
                request.tenantId(), request.name(), request.rateLimitPerSec() == null ? 1000 : request.rateLimitPerSec());
        audit.record(actor, request.tenantId(), "TENANT_CREATED", request.tenantId(), Map.of("name", request.name()));
        return ResponseEntity.status(HttpStatus.CREATED).body(tenant(request.tenantId()));
    }

    @GetMapping("/v1/tenants/{tenantId}")
    Tenant getTenant(@PathVariable String tenantId) {
        if (!CallerTenant.principal().isOperator()) {
            CallerTenant.require(tenantId);
        }
        return tenant(tenantId);
    }

    @PatchMapping("/v1/tenants/{tenantId}")
    Tenant updateTenant(@PathVariable String tenantId, @Valid @RequestBody TenantUpdate update) {
        var actor = CallerTenant.requireOperator();
        int rows = jdbc.update("""
                UPDATE tenant SET name = COALESCE(?, name), rate_limit_per_sec = COALESCE(?, rate_limit_per_sec),
                       updated_at = now()
                WHERE tenant_id = ?
                """, update.name(), update.rateLimitPerSec(), tenantId);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        audit.record(actor, tenantId, "TENANT_UPDATED", tenantId, Map.of("rateLimitPerSec", String.valueOf(update.rateLimitPerSec())));
        return tenant(tenantId);
    }

    @PostMapping("/v1/devices")
    ResponseEntity<Device> register(@RequestHeader(value = CallerTenant.HEADER, required = false) String tenantHeader,
                                    @Valid @RequestBody DeviceRequest request) {
        String tenantId = CallerTenant.require(tenantHeader);
        String secret = insertDevice(tenantId, request);
        Device device = device(tenantId, request.deviceId());
        audit.record(CallerTenant.principal(), tenantId, "DEVICE_REGISTERED", request.deviceId(), Map.of("type", request.deviceType()));
        return ResponseEntity.status(HttpStatus.CREATED).body(withSecret(device, request.secret() == null ? secret : null));
    }

    /** Registers up to 1000 devices in one transaction; existing IDs are reported, not overwritten. */
    @PostMapping("/v1/devices/batch")
    @Transactional
    ResponseEntity<Map<String, Object>> registerBatch(@RequestHeader(value = CallerTenant.HEADER, required = false) String tenantHeader,
                                                      @RequestBody List<@Valid DeviceRequest> requests) {
        String tenantId = CallerTenant.require(tenantHeader);
        if (requests.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + MAX_BATCH + " devices per batch");
        }
        List<Map<String, String>> created = new ArrayList<>();
        int existing = 0;
        for (DeviceRequest request : requests) {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM device WHERE device_id = ?", Integer.class, request.deviceId());
            if (count != null && count > 0) {
                existing++;
                continue;
            }
            String secret = insertDevice(tenantId, request);
            created.add(request.secret() == null ? Map.of("deviceId", request.deviceId(), "secret", secret)
                    : Map.of("deviceId", request.deviceId()));
        }
        audit.record(CallerTenant.principal(), tenantId, "DEVICES_REGISTERED", null, Map.of("count", created.size()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("created", created, "existing", existing));
    }

    @GetMapping("/v1/devices/{deviceId}")
    Device getDevice(@RequestHeader(value = CallerTenant.HEADER, required = false) String tenantHeader,
                     @PathVariable String deviceId) {
        return device(CallerTenant.require(tenantHeader), deviceId);
    }

    @PatchMapping("/v1/devices/{deviceId}")
    Device updateDevice(@RequestHeader(value = CallerTenant.HEADER, required = false) String tenantHeader,
                        @PathVariable String deviceId, @Valid @RequestBody DeviceUpdate update) {
        String tenantId = CallerTenant.require(tenantHeader);
        int rows = jdbc.update("""
                UPDATE device SET firmware = COALESCE(?, firmware), heartbeat_seconds = COALESCE(?, heartbeat_seconds),
                       device_type = COALESCE(?, device_type), updated_at = now()
                WHERE device_id = ? AND tenant_id = ?
                """, update.firmware(), update.heartbeatSeconds(), update.deviceType(), deviceId, tenantId);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        audit.record(CallerTenant.principal(), tenantId, "DEVICE_UPDATED", deviceId, Map.of());
        return device(tenantId, deviceId);
    }

    @PostMapping("/v1/devices/{deviceId}/deactivate")
    Device deactivate(@RequestHeader(value = CallerTenant.HEADER, required = false) String tenantHeader,
                      @PathVariable String deviceId) {
        String tenantId = CallerTenant.require(tenantHeader);
        int rows = jdbc.update("UPDATE device SET active = false, updated_at = now() WHERE device_id = ? AND tenant_id = ?",
                deviceId, tenantId);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        audit.record(CallerTenant.principal(), tenantId, "DEVICE_DEACTIVATED", deviceId, Map.of());
        return device(tenantId, deviceId);
    }

    /** Issues an API key for a tenant. The key is shown once; only its hash is stored. */
    @PostMapping("/v1/tenants/{tenantId}/api-keys")
    ResponseEntity<Map<String, String>> issueKey(@PathVariable String tenantId) {
        var actor = CallerTenant.requireOperator();
        tenant(tenantId);
        ApiKeys.IssuedKey issued = apiKeys.issue(ApiKeys.Role.TENANT, tenantId);
        audit.record(actor, tenantId, "API_KEY_ISSUED", issued.keyId(), Map.of());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("keyId", issued.keyId(), "apiKey", issued.key()));
    }

    @PostMapping("/v1/api-keys/{keyId}/revoke")
    ResponseEntity<Void> revokeKey(@PathVariable String keyId) {
        var actor = CallerTenant.requireOperator();
        if (!apiKeys.revoke(keyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        audit.record(actor, null, "API_KEY_REVOKED", keyId, Map.of());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, String>> onDuplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "ALREADY_EXISTS"));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> onIntegrity(org.springframework.dao.DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("code", "INVALID_REFERENCE",
                "message", "Unknown tenant or device type"));
    }

    private String insertDevice(String tenantId, DeviceRequest request) {
        String secret = request.secret() != null ? request.secret() : newSecret();
        jdbc.update("""
                INSERT INTO device (device_id, tenant_id, device_type, firmware, heartbeat_seconds, home_region, credential_hash)
                VALUES (?, ?, ?, ?, ?, COALESCE(?, 'local'), ?)
                """, request.deviceId(), tenantId, request.deviceType(), request.firmware(), request.heartbeatSeconds(),
                request.homeRegion(), sha256Hex(secret));
        return secret;
    }

    private Tenant tenant(String tenantId) {
        return jdbc.query("SELECT tenant_id, name, rate_limit_per_sec, created_at, updated_at FROM tenant WHERE tenant_id = ?",
                        (rs, n) -> new Tenant(rs.getString(1), rs.getString(2), rs.getInt(3),
                                rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()), tenantId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Device device(String tenantId, String deviceId) {
        return jdbc.query("""
                        SELECT device_id, tenant_id, device_type, firmware, heartbeat_seconds, home_region, active,
                               created_at, updated_at
                        FROM device WHERE device_id = ? AND tenant_id = ?
                        """, (rs, n) -> new Device(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        (Integer) rs.getObject(5), rs.getString(6), rs.getBoolean(7), rs.getTimestamp(8).toInstant(),
                        rs.getTimestamp(9).toInstant(), null), deviceId, tenantId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static Device withSecret(Device d, String secret) {
        return new Device(d.deviceId(), d.tenantId(), d.deviceType(), d.firmware(), d.heartbeatSeconds(), d.homeRegion(),
                d.active(), d.createdAt(), d.updatedAt(), secret);
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
