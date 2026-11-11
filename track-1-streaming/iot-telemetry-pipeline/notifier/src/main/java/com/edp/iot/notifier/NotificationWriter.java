package com.edp.iot.notifier;

import com.edp.iot.common.Json;
import com.edp.iot.common.change.LineChange;
import com.edp.iot.common.notice.CustomerNotice;
import com.edp.iot.common.notify.NotificationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns line changes and customer notices into one notification row per subscribed endpoint. The
 * notification ID is derived from the change and the endpoint, so reading a change twice (Kafka
 * delivers at least once) stores it once, and the customer sees the same {@code notificationId} on
 * every attempt (NFR-10a).
 */
@Component
class NotificationWriter {

    static final String CUSTOMER_KEY = "customer";

    record Pending(UUID notificationId, UUID webhookId, String customerId, NotificationType type, String orderingKey,
                   UUID sourceId, String payload) {
    }

    private final JdbcTemplate jdbc;
    private final WebhookDirectory webhooks;

    NotificationWriter(JdbcTemplate jdbc, WebhookDirectory webhooks) {
        this.jdbc = jdbc;
        this.webhooks = webhooks;
    }

    /** Stores notifications for the changes and notices; returns how many rows were new. */
    int write(List<LineChange> changes, List<CustomerNotice> notices) {
        List<Pending> rows = new ArrayList<>();
        for (LineChange c : changes) {
            NotificationType.of(c).ifPresent(type -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("type", type.name());
                body.put("customerId", c.customerId());
                body.put("lineId", c.iccid());
                body.put("at", c.at().toString());
                c.details().forEach(body::putIfAbsent);
                add(rows, c.customerId(), type, c.iccid(), c.changeId(), body);
            });
        }
        for (CustomerNotice n : notices) {
            NotificationType type = NotificationType.of(n);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", type.name());
            body.put("customerId", n.customerId());
            body.put("at", n.at().toString());
            n.details().forEach(body::putIfAbsent);
            add(rows, n.customerId(), type, CUSTOMER_KEY, n.noticeId(), body);
        }
        if (rows.isEmpty()) {
            return 0;
        }
        int[][] counts = jdbc.batchUpdate("""
                INSERT INTO notification (notification_id, webhook_id, customer_id, type, ordering_key, source_id, payload,
                                          created_at, retry_since, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (notification_id) DO NOTHING
                """, rows, rows.size(), (ps, r) -> {
            Timestamp now = Timestamp.from(Instant.now());
            ps.setObject(1, r.notificationId());
            ps.setObject(2, r.webhookId());
            ps.setString(3, r.customerId());
            ps.setString(4, r.type().name());
            ps.setString(5, r.orderingKey());
            ps.setObject(6, r.sourceId());
            ps.setString(7, r.payload());
            ps.setTimestamp(8, now);
            ps.setTimestamp(9, now);
            ps.setTimestamp(10, now);
        });
        int inserted = 0;
        for (int[] batch : counts) {
            for (int n : batch) {
                inserted += Math.max(0, n);
            }
        }
        return inserted;
    }

    private void add(List<Pending> rows, String customerId, NotificationType type, String orderingKey, UUID sourceId,
                     Map<String, Object> body) {
        for (WebhookDirectory.Endpoint endpoint : webhooks.forCustomer(customerId)) {
            if (!endpoint.types().contains(type.name())) {
                continue; // FR-22: only subscribed types
            }
            UUID id = notificationId(sourceId, endpoint.webhookId());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("notificationId", id.toString());
            payload.putAll(body);
            rows.add(new Pending(id, endpoint.webhookId(), customerId, type, orderingKey, sourceId, Json.write(payload)));
        }
    }

    static UUID notificationId(UUID sourceId, UUID webhookId) {
        return UUID.nameUUIDFromBytes((sourceId + "/" + webhookId).getBytes(StandardCharsets.UTF_8));
    }
}
