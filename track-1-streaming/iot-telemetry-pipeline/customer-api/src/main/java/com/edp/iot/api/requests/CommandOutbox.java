package com.edp.iot.api.requests;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
import com.edp.iot.common.request.LineCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Outbox for {@code line.commands} (ADR-007). The command row is written in the transaction that
 * accepts the request, published right after commit, and marked. A command that could not be
 * published then is re-sent by the relay, so an accepted request always reaches the worker.
 */
@Component
class CommandOutbox {

    private static final Logger log = LoggerFactory.getLogger(CommandOutbox.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final RequestProperties props;

    CommandOutbox(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx, RequestProperties props) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
    }

    /** Call inside the transaction that accepts the request. */
    void insert(LineCommand command) {
        jdbc.update("INSERT INTO line_command (request_id, iccid, payload) VALUES (?, ?, ?)",
                command.requestId(), command.iccid(), Json.write(command));
    }

    /** Publishes after commit. Returns false when Kafka did not confirm in time; the relay retries. */
    boolean publishCommitted(LineCommand command) {
        try {
            send(command.requestId(), command.iccid(), Json.write(command));
            return true;
        } catch (Exception e) {
            log.warn("Publishing command {} failed; the outbox relay will retry: {}", command.requestId(), e.toString());
            return false;
        }
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 2_000)
    void relay() {
        try {
            Integer sent = tx.execute(status -> {
                List<Object[]> rows = jdbc.query("""
                        SELECT request_id, iccid, payload FROM line_command
                        WHERE published_at IS NULL AND created_at < ?
                        ORDER BY created_at LIMIT 500 FOR UPDATE SKIP LOCKED
                        """, (rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)},
                        Timestamp.from(Instant.now().minusSeconds(5)));
                for (Object[] row : rows) {
                    send((UUID) row[0], (String) row[1], (String) row[2]);
                }
                return rows.size();
            });
            if (sent != null && sent > 0) {
                log.info("Command outbox relay re-sent {} commands", sent);
            }
        } catch (RuntimeException e) {
            log.warn("Command outbox relay failed: {}", e.toString());
        }
    }

    private void send(UUID requestId, String iccid, String payload) {
        try {
            kafka.send(Topics.LINE_COMMANDS, iccid, payload).get(props.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Publishing command " + requestId + " failed", e);
        }
        jdbc.update("UPDATE line_command SET published_at = now() WHERE request_id = ?", requestId);
    }
}
