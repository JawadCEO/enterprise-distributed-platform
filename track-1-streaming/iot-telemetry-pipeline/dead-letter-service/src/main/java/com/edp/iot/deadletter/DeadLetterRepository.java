package com.edp.iot.deadletter;

import com.edp.iot.common.deadletter.DeadLetterEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class DeadLetterRepository {

    /** A record without its payload, for listings. */
    record Summary(UUID deadLetterId, String sourceTopic, String deviceId, String tenantId, String reasonCode,
                   String reason, int attempts, Instant firstFailureAt, Instant lastFailureAt, Instant createdAt,
                   int replayCount, Instant lastReplayedAt) {
    }

    record Filter(String reasonCode, String tenantId, String deviceId, Instant from, Instant to) {
    }

    private static final RowMapper<Summary> SUMMARY = (rs, n) -> new Summary(
            rs.getObject("dead_letter_id", UUID.class),
            rs.getString("source_topic"),
            rs.getString("device_id"),
            rs.getString("tenant_id"),
            rs.getString("reason_code"),
            rs.getString("reason"),
            rs.getInt("attempts"),
            rs.getTimestamp("first_failure_at").toInstant(),
            rs.getTimestamp("last_failure_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getInt("replay_count"),
            rs.getTimestamp("last_replayed_at") == null ? null : rs.getTimestamp("last_replayed_at").toInstant());

    private final JdbcTemplate jdbc;

    DeadLetterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: a redelivered DLQ record has the same ID and is ignored. */
    void insert(DeadLetterEnvelope e) {
        jdbc.update("""
                INSERT INTO dead_letter (dead_letter_id, source_topic, device_id, tenant_id, reason_code, reason,
                                         attempts, first_failure_at, last_failure_at, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (dead_letter_id) DO NOTHING
                """, e.deadLetterId(), e.sourceTopic(), e.deviceId(), e.tenantId(), e.reasonCode(), e.reason(),
                e.attempts(), Timestamp.from(e.firstFailureAt()), Timestamp.from(e.lastFailureAt()), e.payload());
    }

    /** Newest first, keyset-paged on (created_at, dead_letter_id). */
    List<Summary> find(Filter f, Instant beforeCreatedAt, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT dead_letter_id, source_topic, device_id, tenant_id, reason_code, reason, attempts,
                       first_failure_at, last_failure_at, created_at, replay_count, last_replayed_at
                FROM dead_letter WHERE true
                """);
        List<Object> args = new ArrayList<>();
        where(f, sql, args);
        if (beforeCreatedAt != null && beforeId != null) {
            sql.append(" AND (created_at, dead_letter_id) < (?, ?)");
            args.add(Timestamp.from(beforeCreatedAt));
            args.add(beforeId);
        }
        sql.append(" ORDER BY created_at DESC, dead_letter_id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), SUMMARY, args.toArray());
    }

    /** IDs and payloads matching a filter, oldest first, for bulk replay. */
    List<Replayable> findReplayable(Filter f, int limit) {
        StringBuilder sql = new StringBuilder("SELECT dead_letter_id, device_id, payload FROM dead_letter WHERE true");
        List<Object> args = new ArrayList<>();
        where(f, sql, args);
        sql.append(" ORDER BY created_at LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, n) -> new Replayable(rs.getObject("dead_letter_id", UUID.class),
                rs.getString("device_id"), rs.getString("payload")), args.toArray());
    }

    Optional<Replayable> findReplayable(UUID id) {
        return jdbc.query("SELECT dead_letter_id, device_id, payload FROM dead_letter WHERE dead_letter_id = ?",
                (rs, n) -> new Replayable(rs.getObject("dead_letter_id", UUID.class), rs.getString("device_id"),
                        rs.getString("payload")), id).stream().findFirst();
    }

    Optional<Summary> find(UUID id) {
        return jdbc.query("""
                SELECT dead_letter_id, source_topic, device_id, tenant_id, reason_code, reason, attempts,
                       first_failure_at, last_failure_at, created_at, replay_count, last_replayed_at
                FROM dead_letter WHERE dead_letter_id = ?
                """, SUMMARY, id).stream().findFirst();
    }

    void markReplayed(UUID id) {
        jdbc.update("UPDATE dead_letter SET replay_count = replay_count + 1, last_replayed_at = now() WHERE dead_letter_id = ?", id);
    }

    int deleteOlderThan(Instant cutoff, int batch) {
        return jdbc.update("""
                DELETE FROM dead_letter WHERE dead_letter_id IN (
                    SELECT dead_letter_id FROM dead_letter WHERE created_at < ? LIMIT ?)
                """, Timestamp.from(cutoff), batch);
    }

    record Replayable(UUID deadLetterId, String deviceId, String payload) {
    }

    private static void where(Filter f, StringBuilder sql, List<Object> args) {
        if (f.reasonCode() != null) {
            sql.append(" AND reason_code = ?");
            args.add(f.reasonCode());
        }
        if (f.tenantId() != null) {
            sql.append(" AND tenant_id = ?");
            args.add(f.tenantId());
        }
        if (f.deviceId() != null) {
            sql.append(" AND device_id = ?");
            args.add(f.deviceId());
        }
        if (f.from() != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            sql.append(" AND created_at < ?");
            args.add(Timestamp.from(f.to()));
        }
    }
}
