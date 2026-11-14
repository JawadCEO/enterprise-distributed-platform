package com.edp.iot.api.webhooks;

import com.edp.iot.api.CallerCustomer;
import com.edp.iot.api.Cursor;
import com.edp.iot.common.Json;
import com.edp.iot.common.notify.NotificationType;
import com.edp.iot.common.notify.WebhookSignature;
import com.edp.iot.common.notify.WebhookUrlPolicy;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook endpoints (FR-22) and the notifications sent to them (FR-24). A customer registers up to
 * {@value #MAX_WEBHOOKS} endpoints, each with the notification types it wants and its own signing
 * secret, which is shown once when the endpoint is created. Notifications stay readable for 30 days
 * whatever happened to them, including parked ones, and a parked one can be sent again.
 */
@RestController
class WebhookController {

    static final int MAX_WEBHOOKS = 10;

    private final JdbcTemplate jdbc;
    private final WebhookUrlPolicy urlPolicy;

    WebhookController(JdbcTemplate jdbc,
                      @Value("${iot.webhooks.allow-insecure-urls:false}") boolean allowInsecureUrls) {
        this.jdbc = jdbc;
        this.urlPolicy = new WebhookUrlPolicy(allowInsecureUrls);
    }

    record WebhookRequest(String url, List<String> types, Boolean active) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WebhookView(UUID webhookId, String url, List<String> types, boolean active, Instant createdAt,
                       Instant updatedAt, String secret) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NotificationView(UUID notificationId, UUID webhookId, String type, String lineId, String status, int attempts,
                            Instant createdAt, Instant lastAttemptAt, Instant deliveredAt, Instant parkedAt,
                            String lastError, Map<String, Object> payload) {
    }

    record Page<T>(List<T> items, String nextCursor) {
    }

    static final class Invalid extends RuntimeException {
        final String code;

        Invalid(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ---- endpoints -----------------------------------------------------------------------------

    @PostMapping("/v1/webhooks")
    ResponseEntity<WebhookView> create(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                                       @RequestBody WebhookRequest body) {
        String customerId = CallerCustomer.require(customerHeader);
        String url = validUrl(body.url());
        String[] types = validTypes(body.types());
        Integer count = jdbc.queryForObject("SELECT count(*) FROM webhook WHERE customer_id = ? AND active",
                Integer.class, customerId);
        if (count != null && count >= MAX_WEBHOOKS) {
            throw new Invalid("TOO_MANY_WEBHOOKS", "At most " + MAX_WEBHOOKS + " active webhooks per customer");
        }
        UUID id = UUID.randomUUID();
        String secret = WebhookSignature.newSecret();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO webhook (webhook_id, customer_id, url, types, secret, active) VALUES (?, ?, ?, ?, ?, ?)");
            ps.setObject(1, id);
            ps.setString(2, customerId);
            ps.setString(3, url);
            ps.setArray(4, con.createArrayOf("text", types));
            ps.setString(5, secret);
            ps.setBoolean(6, body.active() == null || body.active());
            return ps;
        });
        WebhookView created = find(customerId, id);
        return ResponseEntity.created(URI.create("/v1/webhooks/" + id))
                .body(new WebhookView(created.webhookId(), created.url(), created.types(), created.active(),
                        created.createdAt(), created.updatedAt(), secret));
    }

    @GetMapping("/v1/webhooks")
    List<WebhookView> list(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader) {
        String customerId = CallerCustomer.require(customerHeader);
        return jdbc.query("SELECT * FROM webhook WHERE customer_id = ? ORDER BY created_at, webhook_id", WEBHOOK, customerId);
    }

    @GetMapping("/v1/webhooks/{webhookId}")
    WebhookView get(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                    @PathVariable UUID webhookId) {
        return find(CallerCustomer.require(customerHeader), webhookId);
    }

    /** Replaces the URL, types and active flag. The secret stays the same. */
    @PutMapping("/v1/webhooks/{webhookId}")
    WebhookView update(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                       @PathVariable UUID webhookId, @RequestBody WebhookRequest body) {
        String customerId = CallerCustomer.require(customerHeader);
        String url = validUrl(body.url());
        String[] types = validTypes(body.types());
        int updated = jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    UPDATE webhook SET url = ?, types = ?, active = ?, updated_at = now()
                    WHERE webhook_id = ? AND customer_id = ?
                    """);
            ps.setString(1, url);
            ps.setArray(2, con.createArrayOf("text", types));
            ps.setBoolean(3, body.active() == null || body.active());
            ps.setObject(4, webhookId);
            ps.setString(5, customerId);
            return ps;
        });
        if (updated == 0) {
            throw notFound();
        }
        return find(customerId, webhookId);
    }

    /**
     * Stops sending to the endpoint. The row is kept, inactive, so its notifications stay readable
     * for their 30 days; anything still pending for it is parked.
     */
    @DeleteMapping("/v1/webhooks/{webhookId}")
    ResponseEntity<Void> delete(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                                @PathVariable UUID webhookId) {
        String customerId = CallerCustomer.require(customerHeader);
        int updated = jdbc.update("UPDATE webhook SET active = false, updated_at = now() WHERE webhook_id = ? AND customer_id = ?",
                webhookId, customerId);
        if (updated == 0) {
            throw notFound();
        }
        jdbc.update("""
                UPDATE notification SET status = 'PARKED', parked_at = now(), last_error = 'WEBHOOK_REMOVED'
                WHERE webhook_id = ? AND status = 'PENDING'
                """, webhookId);
        return ResponseEntity.noContent().build();
    }

    /** Notifications, newest first, filtered by status, type or line. */
    @GetMapping("/v1/notifications")
    Page<NotificationView> notifications(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String type,
                                         @RequestParam(required = false) String lineId,
                                         @RequestParam(required = false) UUID webhookId,
                                         @RequestParam(defaultValue = "100") int limit,
                                         @RequestParam(required = false) String cursor) {
        String customerId = CallerCustomer.require(customerHeader);
        int size = Math.max(1, Math.min(limit, 1000));
        StringBuilder sql = new StringBuilder("SELECT * FROM notification WHERE customer_id = ?");
        List<Object> args = new ArrayList<>(List.of(customerId));
        if (status != null) {
            if (!List.of("PENDING", "DELIVERED", "PARKED").contains(status)) {
                throw new Invalid("INVALID_STATUS", "status is PENDING, DELIVERED or PARKED");
            }
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (type != null) {
            sql.append(" AND type = ?");
            args.add(NotificationType.parse(type)
                    .orElseThrow(() -> new Invalid("INVALID_TYPE", "Unknown notification type " + type)).name());
        }
        if (lineId != null) {
            sql.append(" AND ordering_key = ?");
            args.add(lineId);
        }
        if (webhookId != null) {
            sql.append(" AND webhook_id = ?");
            args.add(webhookId);
        }
        if (cursor != null) {
            String[] parts = Cursor.decode(cursor, 2);
            sql.append(" AND (created_at, notification_id) < (?, ?)");
            args.add(Timestamp.from(Instant.parse(parts[0])));
            args.add(UUID.fromString(parts[1]));
        }
        sql.append(" ORDER BY created_at DESC, notification_id DESC LIMIT ?");
        args.add(size + 1);
        List<NotificationView> rows = jdbc.query(sql.toString(), NOTIFICATION, args.toArray());
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            NotificationView last = rows.get(size - 1);
            next = Cursor.encode(last.createdAt(), last.notificationId());
        }
        return new Page<>(rows, next);
    }

    @GetMapping("/v1/notifications/{notificationId}")
    NotificationView notification(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                                  @PathVariable UUID notificationId) {
        String customerId = CallerCustomer.require(customerHeader);
        return jdbc.query("SELECT * FROM notification WHERE notification_id = ? AND customer_id = ?", NOTIFICATION,
                notificationId, customerId).stream().findFirst().orElseThrow(WebhookController::notFound);
    }

    /** Sends a parked notification again, with a fresh retry period, to its endpoint if that is still active. */
    @PostMapping("/v1/notifications/{notificationId}/redeliver")
    ResponseEntity<NotificationView> redeliver(@RequestHeader(value = CallerCustomer.HEADER, required = false) String customerHeader,
                                               @PathVariable UUID notificationId) {
        String customerId = CallerCustomer.require(customerHeader);
        NotificationView current = notification(customerHeader, notificationId);
        if (!"PARKED".equals(current.status())) {
            throw new Invalid("NOT_PARKED", "Only a parked notification can be sent again; this one is " + current.status());
        }
        int updated = jdbc.update("""
                UPDATE notification n
                   SET status = 'PENDING', retry_since = now(), next_attempt_at = now(), parked_at = NULL, lease_until = NULL
                  FROM webhook w
                 WHERE n.notification_id = ? AND n.customer_id = ? AND n.status = 'PARKED'
                   AND w.webhook_id = n.webhook_id AND w.active
                """, notificationId, customerId);
        if (updated == 0) {
            throw new Invalid("WEBHOOK_INACTIVE", "The notification's webhook is no longer active");
        }
        return ResponseEntity.accepted().body(notification(customerHeader, notificationId));
    }

    @ExceptionHandler(Invalid.class)
    ResponseEntity<Map<String, Object>> onInvalid(Invalid e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code);
        body.put("message", e.getMessage());
        HttpStatus status = e.code.equals("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : e.code.equals("NOT_PARKED") || e.code.equals("WEBHOOK_INACTIVE") || e.code.equals("TOO_MANY_WEBHOOKS")
                ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(body);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private WebhookView find(String customerId, UUID webhookId) {
        return jdbc.query("SELECT * FROM webhook WHERE webhook_id = ? AND customer_id = ?", WEBHOOK, webhookId, customerId)
                .stream().findFirst().orElseThrow(WebhookController::notFound);
    }

    private static Invalid notFound() {
        return new Invalid("NOT_FOUND", "No such webhook or notification for this customer");
    }

    /**
     * An absolute https URL whose host resolves only to public addresses (WebhookUrlPolicy), so an
     * endpoint can't point the notifier at internal services. The local stack also allows plain http
     * to private addresses, for the simulated customers.
     */
    String validUrl(String url) {
        urlPolicy.check(url).ifPresent(r -> {
            throw new Invalid(r.code(), r.message());
        });
        return URI.create(url).toString();
    }

    static String[] validTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            throw new Invalid("INVALID_TYPES", "types must name at least one of " + Arrays.toString(NotificationType.values()));
        }
        return types.stream().distinct().map(t -> NotificationType.parse(t)
                        .orElseThrow(() -> new Invalid("INVALID_TYPES", "Unknown notification type " + t)).name())
                .toArray(String[]::new);
    }

    private static final RowMapper<WebhookView> WEBHOOK = (rs, n) -> {
        Array types = rs.getArray("types");
        return new WebhookView(rs.getObject("webhook_id", UUID.class), rs.getString("url"),
                List.of((String[]) types.getArray()), rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), null);
    };

    private static final RowMapper<NotificationView> NOTIFICATION = (rs, n) -> {
        String key = rs.getString("ordering_key");
        return new NotificationView(rs.getObject("notification_id", UUID.class), rs.getObject("webhook_id", UUID.class),
                rs.getString("type"), "customer".equals(key) ? null : key, rs.getString("status"), rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("last_attempt_at")),
                instant(rs.getTimestamp("delivered_at")), instant(rs.getTimestamp("parked_at")), rs.getString("last_error"),
                Json.readMap(rs.getString("payload")));
    };

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
