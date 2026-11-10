package com.edp.iot.common.change;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
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
 * Transactional outbox for {@code line.changes}, using {@code line_history} itself as the outbox.
 * A change row is written in the same transaction as the state it describes, published right
 * after commit, and marked published. If publishing fails, the relay finds the unpublished row
 * later and sends it again, so no change is lost.
 */
public class LineChangeOutbox {

    private static final Logger log = LoggerFactory.getLogger(LineChangeOutbox.class);

    private static final String INSERT = """
            INSERT INTO line_history (change_id, iccid, customer_id, kind, notify, event_id, at, details)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

    private static final String SELECT_UNPUBLISHED = """
            SELECT change_id, iccid, customer_id, kind, notify, event_id, at, details::text AS details
            FROM line_history
            WHERE published_at IS NULL AND at < ?
            ORDER BY at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final Duration publishTimeout;

    public LineChangeOutbox(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                            Duration publishTimeout) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.publishTimeout = publishTimeout;
    }

    /** Writes change rows. Call inside the transaction that changes the state. */
    public void insert(List<LineChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT, changes, changes.size(), (ps, c) -> {
            ps.setObject(1, c.changeId());
            ps.setString(2, c.iccid());
            ps.setString(3, c.customerId());
            ps.setString(4, c.kind().name());
            ps.setBoolean(5, c.notifyCustomer());
            ps.setObject(6, c.eventId());
            ps.setTimestamp(7, Timestamp.from(c.at()));
            ps.setString(8, Json.write(c.details()));
        });
    }

    /**
     * Publishes changes after their transaction committed, then marks them. A failure here is only
     * logged: the relay picks the rows up.
     */
    public void publishCommitted(List<LineChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        try {
            send(changes);
            markPublished(changes);
        } catch (Exception e) {
            log.warn("Publishing {} line changes failed; the outbox relay will retry: {}", changes.size(), e.toString());
        }
    }

    /** Re-sends changes that were committed but never marked published. Returns how many. */
    public int relay(Duration olderThan, int limit) {
        Integer sent = tx.execute(status -> {
            Instant cutoff = Instant.now().minus(olderThan);
            List<LineChange> pending = jdbc.query(SELECT_UNPUBLISHED, (rs, n) -> new LineChange(
                    rs.getObject("change_id", UUID.class),
                    rs.getString("iccid"),
                    rs.getString("customer_id"),
                    ChangeKind.valueOf(rs.getString("kind")),
                    rs.getBoolean("notify"),
                    rs.getObject("event_id", UUID.class),
                    rs.getTimestamp("at").toInstant(),
                    Json.readMap(rs.getString("details"))), Timestamp.from(cutoff), limit);
            if (pending.isEmpty()) {
                return 0;
            }
            send(pending);
            markPublished(pending);
            return pending.size();
        });
        return sent == null ? 0 : sent;
    }

    private void send(List<LineChange> changes) {
        List<CompletableFuture<?>> futures = new ArrayList<>(changes.size());
        for (LineChange c : changes) {
            futures.add(kafka.send(Topics.LINE_CHANGES, c.iccid(), Json.write(c)));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing line changes", e);
        } catch (Exception e) {
            throw new IllegalStateException("Publishing line changes failed", e);
        }
    }

    private void markPublished(List<LineChange> changes) {
        Instant earliest = changes.stream().map(LineChange::at).min(Instant::compareTo).orElseThrow();
        UUID[] ids = changes.stream().map(LineChange::changeId).toArray(UUID[]::new);
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "UPDATE line_history SET published_at = now() WHERE change_id = ANY(?) AND at >= ?");
            Array array = con.createArrayOf("uuid", ids);
            ps.setArray(1, array);
            ps.setTimestamp(2, Timestamp.from(earliest));
            return ps;
        });
    }
}
