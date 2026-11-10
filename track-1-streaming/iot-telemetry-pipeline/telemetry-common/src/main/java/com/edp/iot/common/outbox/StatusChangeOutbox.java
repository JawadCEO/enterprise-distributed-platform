package com.edp.iot.common.outbox;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
import com.edp.iot.common.status.ChangeCause;
import com.edp.iot.common.status.DeviceStatus;
import com.edp.iot.common.status.StatusChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Transactional outbox for status changes. A change row is written in the same transaction as the
 * status update, published right after commit, and marked published. If publishing fails, the
 * relay finds the unpublished row later and sends it again, so no change is lost.
 */
public class StatusChangeOutbox {

    private static final Logger log = LoggerFactory.getLogger(StatusChangeOutbox.class);

    private static final String INSERT = """
            INSERT INTO status_change (change_id, device_id, tenant_id, from_status, to_status, cause,
                                       boot_id, sequence, changed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_UNPUBLISHED = """
            SELECT change_id, device_id, tenant_id, from_status, to_status, cause, boot_id, sequence, changed_at
            FROM status_change
            WHERE published_at IS NULL AND changed_at < ?
            ORDER BY changed_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final Duration publishTimeout;

    public StatusChangeOutbox(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                              Duration publishTimeout) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.publishTimeout = publishTimeout;
    }

    /** Writes change rows. Call inside the transaction that updates device_status. */
    public void insert(List<StatusChangeEvent> changes) {
        if (changes.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT, changes, changes.size(), (ps, c) -> {
            ps.setObject(1, c.changeId());
            ps.setString(2, c.deviceId());
            ps.setString(3, c.tenantId());
            ps.setString(4, c.from() == null ? null : c.from().name());
            ps.setString(5, c.to().name());
            ps.setString(6, c.cause().name());
            ps.setLong(7, c.bootId());
            ps.setLong(8, c.sequence());
            ps.setTimestamp(9, Timestamp.from(c.changedAt()));
        });
    }

    /**
     * Publishes changes after their transaction committed, then marks them. A failure here is only
     * logged: the relay picks the rows up.
     */
    public void publishCommitted(List<StatusChangeEvent> changes) {
        if (changes.isEmpty()) {
            return;
        }
        try {
            send(changes);
            markPublished(changes);
        } catch (Exception e) {
            log.warn("Publishing {} status changes failed; the outbox relay will retry: {}", changes.size(), e.toString());
        }
    }

    /** Re-sends changes that were committed but never marked published. Returns how many. */
    public int relay(Duration olderThan, int limit) {
        Integer sent = tx.execute(status -> {
            Instant cutoff = Instant.now().minus(olderThan);
            List<StatusChangeEvent> pending = jdbc.query(SELECT_UNPUBLISHED, (rs, n) -> new StatusChangeEvent(
                    rs.getObject("change_id", UUID.class),
                    rs.getString("device_id"),
                    rs.getString("tenant_id"),
                    rs.getString("from_status") == null ? null : DeviceStatus.valueOf(rs.getString("from_status")),
                    DeviceStatus.valueOf(rs.getString("to_status")),
                    ChangeCause.valueOf(rs.getString("cause")),
                    rs.getLong("boot_id"),
                    rs.getLong("sequence"),
                    rs.getTimestamp("changed_at").toInstant()), Timestamp.from(cutoff), limit);
            if (pending.isEmpty()) {
                return 0;
            }
            send(pending);
            markPublished(pending);
            return pending.size();
        });
        return sent == null ? 0 : sent;
    }

    private void send(List<StatusChangeEvent> changes) {
        List<CompletableFuture<?>> futures = new ArrayList<>(changes.size());
        for (StatusChangeEvent c : changes) {
            futures.add(kafka.send(Topics.STATUS_CHANGED, c.deviceId(), Json.write(c)));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing status changes", e);
        } catch (Exception e) {
            throw new IllegalStateException("Publishing status changes failed", e);
        }
    }

    private void markPublished(List<StatusChangeEvent> changes) {
        Instant earliest = changes.stream().map(StatusChangeEvent::changedAt).min(Instant::compareTo).orElseThrow();
        UUID[] ids = changes.stream().map(StatusChangeEvent::changeId).toArray(UUID[]::new);
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "UPDATE status_change SET published_at = now() WHERE change_id = ANY(?) AND changed_at >= ?");
            Array array = con.createArrayOf("uuid", ids);
            ps.setArray(1, array);
            ps.setTimestamp(2, Timestamp.from(earliest));
            return ps;
        });
    }
}
