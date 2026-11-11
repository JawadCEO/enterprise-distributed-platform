package com.edp.iot.notifier;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Each customer's active endpoints, cached briefly: a new or changed subscription applies within the TTL. */
@Component
class WebhookDirectory {

    record Endpoint(UUID webhookId, String customerId, String url, Set<String> types, String secret) {
    }

    private final JdbcTemplate jdbc;
    private final LoadingCache<String, List<Endpoint>> byCustomer;
    private final LoadingCache<UUID, Endpoint> byId;

    WebhookDirectory(JdbcTemplate jdbc, NotifierProperties props) {
        this.jdbc = jdbc;
        this.byCustomer = Caffeine.newBuilder().maximumSize(100_000).expireAfterWrite(props.webhookCacheTtl())
                .build(this::loadCustomer);
        this.byId = Caffeine.newBuilder().maximumSize(100_000).expireAfterWrite(props.webhookCacheTtl())
                .build(this::loadOne);
    }

    List<Endpoint> forCustomer(String customerId) {
        return byCustomer.get(customerId);
    }

    /** The endpoint if it is still active, else null. */
    Endpoint active(UUID webhookId) {
        return byId.get(webhookId);
    }

    private List<Endpoint> loadCustomer(String customerId) {
        return jdbc.query("SELECT webhook_id, customer_id, url, types, secret FROM webhook WHERE customer_id = ? AND active",
                (rs, n) -> endpoint(rs), customerId);
    }

    private Endpoint loadOne(UUID webhookId) {
        return jdbc.query("SELECT webhook_id, customer_id, url, types, secret FROM webhook WHERE webhook_id = ? AND active",
                (rs, n) -> endpoint(rs), webhookId).stream().findFirst().orElse(null);
    }

    private static Endpoint endpoint(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Endpoint(rs.getObject("webhook_id", UUID.class), rs.getString("customer_id"), rs.getString("url"),
                Set.of((String[]) rs.getArray("types").getArray()), rs.getString("secret"));
    }
}
