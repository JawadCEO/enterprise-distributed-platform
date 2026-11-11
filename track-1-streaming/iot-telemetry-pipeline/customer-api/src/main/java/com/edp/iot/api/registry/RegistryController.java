package com.edp.iot.api.registry;

import com.edp.iot.api.CallerCustomer;
import com.edp.iot.common.line.LineState;
import com.edp.iot.common.line.SimType;
import com.edp.iot.common.security.ApiKeys;
import com.edp.iot.common.security.AuditLog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Registry of customers and lines (FR-1, FR-3) and API keys. Changes reach event-ingest and
 * state-processor within their registry cache TTL (30 seconds by default).
 *
 * <p>Lines are imported by an operator, the way a carrier loads a batch of SIMs into a customer's
 * account. They start in {@code INVENTORY}; an operator may import lines already {@code ACTIVE}
 * on the network. Every later line-state change goes through a customer request (part 2b) or the
 * automatic suspension on a SIM move.
 */
@RestController
class RegistryController {

    private static final int MAX_BATCH = 1000;

    private final JdbcTemplate jdbc;
    private final ApiKeys apiKeys;
    private final AuditLog audit;

    RegistryController(JdbcTemplate jdbc, ApiKeys apiKeys, AuditLog audit) {
        this.jdbc = jdbc;
        this.apiKeys = apiKeys;
        this.audit = audit;
    }

    record CustomerRequest(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String customerId,
                           @NotBlank @Size(max = 200) String name,
                           @Positive Integer rateLimitPerSec,
                           Boolean autoSuspendOnSimMove) {
    }

    record CustomerUpdate(@Size(max = 200) String name, @Positive Integer rateLimitPerSec, Boolean autoSuspendOnSimMove,
                          @Pattern(regexp = "GOOD|BLOCKED") String accountStatus) {
    }

    record Customer(String customerId, String name, int rateLimitPerSec, boolean autoSuspendOnSimMove,
                    String accountStatus, Instant createdAt, Instant updatedAt) {
    }

    record LineRequest(@NotBlank @Pattern(regexp = "[0-9]{18,22}") String iccid,
                       @NotNull SimType simType,
                       @NotBlank String deviceKind,
                       @Positive Integer keepAliveSeconds,
                       @Size(max = 32) String planCode,
                       @PositiveOrZero Long dataAllowanceBytes,
                       @Size(max = 32) String homeRegion,
                       LineState initialState) {
    }

    record LineUpdate(@Positive Integer keepAliveSeconds, @PositiveOrZero Long dataAllowanceBytes,
                      @Size(max = 32) String planCode) {
    }

    @PostMapping("/v1/customers")
    ResponseEntity<Customer> createCustomer(@Valid @RequestBody CustomerRequest request) {
        var actor = CallerCustomer.requireOperator();
        jdbc.update("INSERT INTO customer (customer_id, name, rate_limit_per_sec, auto_suspend_on_sim_move) VALUES (?, ?, ?, ?)",
                request.customerId(), request.name(), request.rateLimitPerSec() == null ? 1000 : request.rateLimitPerSec(),
                Boolean.TRUE.equals(request.autoSuspendOnSimMove()));
        audit.record(actor, request.customerId(), "CUSTOMER_CREATED", request.customerId(), Map.of("name", request.name()));
        return ResponseEntity.status(HttpStatus.CREATED).body(customer(request.customerId()));
    }

    @GetMapping("/v1/customers/{customerId}")
    Customer getCustomer(@PathVariable String customerId) {
        if (!CallerCustomer.principal().isOperator()) {
            CallerCustomer.require(customerId);
        }
        return customer(customerId);
    }

    @PatchMapping("/v1/customers/{customerId}")
    Customer updateCustomer(@PathVariable String customerId, @Valid @RequestBody CustomerUpdate update) {
        var actor = CallerCustomer.requireOperator();
        int rows = jdbc.update("""
                UPDATE customer SET name = COALESCE(?, name), rate_limit_per_sec = COALESCE(?, rate_limit_per_sec),
                       auto_suspend_on_sim_move = COALESCE(?, auto_suspend_on_sim_move),
                       account_status = COALESCE(?, account_status), updated_at = now()
                WHERE customer_id = ?
                """, update.name(), update.rateLimitPerSec(), update.autoSuspendOnSimMove(), update.accountStatus(), customerId);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        audit.record(actor, customerId, "CUSTOMER_UPDATED", customerId, Map.of(
                "rateLimitPerSec", String.valueOf(update.rateLimitPerSec()),
                "autoSuspendOnSimMove", String.valueOf(update.autoSuspendOnSimMove()),
                "accountStatus", String.valueOf(update.accountStatus())));
        return customer(customerId);
    }

    /** Imports up to 1000 lines into a customer's account in one transaction; existing ICCIDs are reported, not changed. */
    @PostMapping("/v1/lines/batch")
    @Transactional
    ResponseEntity<Map<String, Object>> importLines(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                                                    @RequestBody List<@Valid LineRequest> requests) {
        var actor = CallerCustomer.requireOperator();
        String customerId = CallerCustomer.require(customerHeader);
        if (requests.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + MAX_BATCH + " lines per batch");
        }
        int created = 0;
        int existing = 0;
        for (LineRequest r : requests) {
            LineState state = r.initialState() == null ? LineState.INVENTORY : r.initialState();
            if (state != LineState.INVENTORY && state != LineState.ACTIVE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "initialState must be INVENTORY or ACTIVE");
            }
            int rows = jdbc.update("""
                    INSERT INTO line (iccid, customer_id, sim_type, device_kind, keep_alive_seconds, plan_code,
                                      data_allowance_bytes, home_region, line_state)
                    VALUES (?, ?, ?, ?, ?, COALESCE(?, 'IOT-STANDARD'), COALESCE(?, 0), COALESCE(?, 'local'), ?)
                    ON CONFLICT (iccid) DO NOTHING
                    """, r.iccid(), customerId, r.simType().name(), r.deviceKind(), r.keepAliveSeconds(), r.planCode(),
                    r.dataAllowanceBytes(), r.homeRegion(), state.name());
            if (rows == 0) {
                existing++;
            } else {
                created++;
            }
        }
        audit.record(actor, customerId, "LINES_IMPORTED", null, Map.of("created", created, "existing", existing));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("created", created, "existing", existing));
    }

    @PatchMapping("/v1/lines/{iccid}")
    Map<String, Object> updateLine(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                                   @PathVariable String iccid, @Valid @RequestBody LineUpdate update) {
        var actor = CallerCustomer.requireOperator();
        String customerId = CallerCustomer.require(customerHeader);
        int rows = jdbc.update("""
                UPDATE line SET keep_alive_seconds = COALESCE(?, keep_alive_seconds),
                       data_allowance_bytes = COALESCE(?, data_allowance_bytes), plan_code = COALESCE(?, plan_code),
                       updated_at = now()
                WHERE iccid = ? AND customer_id = ?
                """, update.keepAliveSeconds(), update.dataAllowanceBytes(), update.planCode(), iccid, customerId);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        audit.record(actor, customerId, "LINE_UPDATED", iccid, Map.of());
        return Map.of("iccid", iccid, "updated", true);
    }

    /** Issues an API key for a customer's systems. The key is shown once; only its hash is stored. */
    @PostMapping("/v1/customers/{customerId}/api-keys")
    ResponseEntity<Map<String, String>> issueCustomerKey(@PathVariable String customerId) {
        var actor = CallerCustomer.requireOperator();
        customer(customerId);
        ApiKeys.IssuedKey issued = apiKeys.issue(ApiKeys.Role.CUSTOMER, customerId);
        audit.record(actor, customerId, "API_KEY_ISSUED", issued.keyId(), Map.of("role", "CUSTOMER"));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("keyId", issued.keyId(), "apiKey", issued.key()));
    }

    /** Issues a key for a network sender (a network element or the simulator) to post events to event-ingest. */
    @PostMapping("/v1/network-keys")
    ResponseEntity<Map<String, String>> issueNetworkKey() {
        var actor = CallerCustomer.requireOperator();
        ApiKeys.IssuedKey issued = apiKeys.issue(ApiKeys.Role.NETWORK, null);
        audit.record(actor, null, "API_KEY_ISSUED", issued.keyId(), Map.of("role", "NETWORK"));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("keyId", issued.keyId(), "apiKey", issued.key()));
    }

    @PostMapping("/v1/api-keys/{keyId}/revoke")
    ResponseEntity<Void> revokeKey(@PathVariable String keyId) {
        var actor = CallerCustomer.requireOperator();
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

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> onIntegrity(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("code", "INVALID_REFERENCE",
                "message", "Unknown customer or device kind, or a value out of range"));
    }

    private Customer customer(String customerId) {
        return jdbc.query("""
                        SELECT customer_id, name, rate_limit_per_sec, auto_suspend_on_sim_move, account_status, created_at, updated_at
                        FROM customer WHERE customer_id = ?
                        """, (rs, n) -> new Customer(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant()), customerId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
