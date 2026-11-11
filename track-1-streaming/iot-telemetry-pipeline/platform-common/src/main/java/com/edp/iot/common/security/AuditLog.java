package com.edp.iot.common.security;

import com.edp.iot.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/** Append-only record of who changed the registry or replayed dead letters, and when (NFR-19). */
public class AuditLog {

    private final JdbcTemplate jdbc;

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(ApiKeys.Principal actor, String customerId, String action, String target, Map<String, ?> details) {
        jdbc.update("INSERT INTO audit_log (actor, role, customer_id, action, target, details) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                actor.keyId(), actor.role().name(), customerId, action, target, Json.write(details == null ? Map.of() : details));
    }
}
