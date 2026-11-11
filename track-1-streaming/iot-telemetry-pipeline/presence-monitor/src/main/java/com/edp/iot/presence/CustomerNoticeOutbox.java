package com.edp.iot.presence;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
import com.edp.iot.common.notice.CustomerNotice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Outbox for {@code customer.notices}: a notice is written in the same transaction as the outage
 * row it describes, published after commit, and re-sent by the relay if publishing failed.
 */
@Component
class CustomerNoticeOutbox {

    private static final Logger log = LoggerFactory.getLogger(CustomerNoticeOutbox.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;

    CustomerNoticeOutbox(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
    }

    /** Call inside the transaction that records the outage change. */
    void insert(List<CustomerNotice> notices) {
        jdbc.batchUpdate("INSERT INTO customer_notice (notice_id, customer_id, kind, at, details) VALUES (?, ?, ?, ?, ?::jsonb)",
                notices, Math.max(1, notices.size()), (ps, n) -> {
                    ps.setObject(1, n.noticeId());
                    ps.setString(2, n.customerId());
                    ps.setString(3, n.kind().name());
                    ps.setTimestamp(4, Timestamp.from(n.at()));
                    ps.setString(5, Json.write(n.details()));
                });
    }

    void publishCommitted(List<CustomerNotice> notices) {
        if (notices.isEmpty()) {
            return;
        }
        try {
            send(notices);
        } catch (Exception e) {
            log.warn("Publishing {} customer notices failed; the relay will retry: {}", notices.size(), e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${iot.presence.notice-relay-interval-ms:5000}")
    void relay() {
        try {
            tx.executeWithoutResult(status -> {
                List<CustomerNotice> pending = jdbc.query("""
                        SELECT notice_id, customer_id, kind, at, details::text AS details FROM customer_notice
                        WHERE published_at IS NULL AND at < now() - interval '10 seconds'
                        ORDER BY at LIMIT 500 FOR UPDATE SKIP LOCKED
                        """, (rs, n) -> new CustomerNotice(rs.getObject("notice_id", UUID.class), rs.getString("customer_id"),
                        CustomerNotice.Kind.valueOf(rs.getString("kind")), rs.getTimestamp("at").toInstant(),
                        Json.readMap(rs.getString("details"))));
                if (!pending.isEmpty()) {
                    send(pending);
                    log.info("Relayed {} customer notices", pending.size());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Customer notice relay failed: {}", e.toString());
        }
    }

    private void send(List<CustomerNotice> notices) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (CustomerNotice n : notices) {
            futures.add(kafka.send(Topics.CUSTOMER_NOTICES, n.customerId(), Json.write(n)));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing customer notices", e);
        } catch (Exception e) {
            throw new IllegalStateException("Publishing customer notices failed", e);
        }
        UUID[] ids = notices.stream().map(CustomerNotice::noticeId).toArray(UUID[]::new);
        jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE customer_notice SET published_at = now() WHERE notice_id = ANY(?)");
            ps.setArray(1, con.createArrayOf("uuid", ids));
            return ps;
        });
    }

    static CustomerNotice notice(String customerId, CustomerNotice.Kind kind, Instant at, java.util.Map<String, Object> details) {
        return new CustomerNotice(UUID.randomUUID(), customerId, kind, at, details);
    }
}
