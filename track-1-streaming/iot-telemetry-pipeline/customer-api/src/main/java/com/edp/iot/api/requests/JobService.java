package com.edp.iot.api.requests;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
import com.edp.iot.common.notice.CustomerNotice;
import com.edp.iot.common.request.RequestAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Bulk jobs (FR-9): one action on up to 50,000 of a customer's lines. Creating a job stores it and
 * its lines and returns at once. The runner then feeds the lines to the ordinary request path, with
 * the same checks and the same outcome per line, keeping at most {@code maxInFlight} of the job's
 * requests waiting on the network. provisioning-worker paces every call to the network, so the
 * network never sees more than its configured rate whatever the size of the job.
 *
 * <p>When every line has an outcome the job is marked {@code COMPLETED} and one
 * {@code BULK_JOB_SUMMARY} notice goes to the customer, with the succeeded and failed counts. Each
 * line that failed on the network also gets its own {@code REQUEST_FAILED} webhook; lines that
 * succeed send none (Flow E step 5).
 */
@Service
class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final Pattern ICCID = Pattern.compile("^[0-9]{18,22}$");

    record Job(UUID jobId, String customerId, RequestAction action, String profile, String status, int total,
               int queued, int inProgress, int succeeded, int failed, int rejected, Instant createdAt, Instant completedAt) {
    }

    record Failure(String iccid, String reason, UUID requestId) {
    }

    record Created(Job job, boolean replay) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RequestService requests;
    private final KafkaTemplate<String, String> kafka;
    private final JobProperties props;

    JobService(JdbcTemplate jdbc, TransactionTemplate tx, RequestService requests, KafkaTemplate<String, String> kafka,
               JobProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.requests = requests;
        this.kafka = kafka;
        this.props = props;
    }

    // ---- creating and reading jobs -------------------------------------------------------------

    Created create(String customerId, RequestAction action, String profile, List<String> iccids, String idempotencyKey) {
        Optional<Job> earlier = byKey(customerId, idempotencyKey);
        if (earlier.isPresent()) {
            return new Created(earlier.get(), true);
        }
        requests.checkProfile(action, profile);
        if (iccids == null || iccids.isEmpty()) {
            throw new RequestRejected(HttpStatus.BAD_REQUEST, "NO_LINES", "Name at least one line in iccids", Map.of());
        }
        Set<String> lines = new LinkedHashSet<>(iccids);
        if (lines.size() > props.maxLines()) {
            throw new RequestRejected(HttpStatus.BAD_REQUEST, "TOO_MANY_LINES",
                    "A job takes at most " + props.maxLines() + " lines", Map.of("maxLines", props.maxLines()));
        }
        List<String> malformed = lines.stream().filter(i -> i == null || !ICCID.matcher(i).matches()).limit(10).toList();
        if (!malformed.isEmpty()) {
            throw new RequestRejected(HttpStatus.BAD_REQUEST, "INVALID_ICCID", "Every ICCID is 18 to 22 digits",
                    Map.of("examples", malformed));
        }
        UUID jobId = UUID.randomUUID();
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update("""
                        INSERT INTO job (job_id, customer_id, action, profile, status, total, idempotency_key)
                        VALUES (?, ?, ?, ?, 'RUNNING', ?, ?)
                        """, jobId, customerId, action.name(), profile, lines.size(), idempotencyKey);
                jdbc.update(con -> {
                    var ps = con.prepareStatement(
                            "INSERT INTO job_item (job_id, iccid, status) SELECT ?, unnest(?::text[]), 'QUEUED'");
                    ps.setObject(1, jobId);
                    ps.setArray(2, con.createArrayOf("text", lines.toArray()));
                    return ps;
                });
            });
        } catch (DuplicateKeyException e) {
            return byKey(customerId, idempotencyKey).map(j -> new Created(j, true)).orElseThrow(() -> e);
        }
        log.info("Job {} for {}: {} on {} lines", jobId, customerId, action, lines.size());
        return new Created(find(customerId, jobId).orElseThrow(), false);
    }

    Optional<Job> find(String customerId, UUID jobId) {
        return jdbc.query("SELECT job_id, customer_id, action, profile, status, total, created_at, completed_at FROM job "
                        + "WHERE job_id = ? AND customer_id = ?", (rs, n) -> new Object[]{
                        rs.getObject("job_id", UUID.class), rs.getString("customer_id"), rs.getString("action"),
                        rs.getString("profile"), rs.getString("status"), rs.getInt("total"), rs.getTimestamp("created_at"),
                        rs.getTimestamp("completed_at")}, jobId, customerId)
                .stream().findFirst().map(r -> {
                    Counts c = counts(jobId);
                    return new Job((UUID) r[0], (String) r[1], RequestAction.valueOf((String) r[2]), (String) r[3],
                            (String) r[4], (Integer) r[5], c.queued, c.pending, c.completed, c.failed + c.rejected,
                            c.rejected, ((Timestamp) r[6]).toInstant(), r[7] == null ? null : ((Timestamp) r[7]).toInstant());
                });
    }

    /** The job's failed lines with their reasons, by ICCID: refused before the network, or failed on it. */
    List<Failure> failures(String customerId, UUID jobId, String after, int limit) {
        return jdbc.query("""
                SELECT i.iccid, COALESCE(i.reason, r.reason) AS reason, i.request_id
                  FROM job_item i
                  JOIN job j ON j.job_id = i.job_id AND j.customer_id = ?
                  LEFT JOIN line_request r ON r.request_id = i.request_id
                 WHERE i.job_id = ? AND (i.status = 'REJECTED' OR r.status = 'FAILED') AND i.iccid > ?
                 ORDER BY i.iccid LIMIT ?
                """, (rs, n) -> new Failure(rs.getString("iccid"), rs.getString("reason"), rs.getObject("request_id", UUID.class)),
                customerId, jobId, after == null ? "" : after, limit);
    }

    private Optional<Job> byKey(String customerId, String idempotencyKey) {
        return jdbc.queryForList("SELECT job_id FROM job WHERE customer_id = ? AND idempotency_key = ?", UUID.class,
                customerId, idempotencyKey).stream().findFirst().flatMap(id -> find(customerId, id));
    }

    private record Counts(int queued, int rejected, int pending, int completed, int failed) {
    }

    private Counts counts(UUID jobId) {
        int[] c = new int[5];
        jdbc.query("SELECT status, count(*) FROM job_item WHERE job_id = ? AND status <> 'SUBMITTED' GROUP BY status", rs -> {
            c["QUEUED".equals(rs.getString(1)) ? 0 : 1] = rs.getInt(2);
        }, jobId);
        jdbc.query("SELECT status, count(*) FROM line_request WHERE job_id = ? GROUP BY status", rs -> {
            switch (rs.getString(1)) {
                case "PENDING" -> c[2] = rs.getInt(2);
                case "COMPLETED" -> c[3] = rs.getInt(2);
                default -> c[4] = rs.getInt(2);
            }
        }, jobId);
        return new Counts(c[0], c[1], c[2], c[3], c[4]);
    }

    // ---- the runner ----------------------------------------------------------------------------

    private record Claimed(UUID jobId, String customerId, RequestAction action, String profile) {
    }

    @Scheduled(initialDelay = 5_000, fixedDelayString = "${iot.jobs.tick-ms:1000}")
    void run() {
        List<Claimed> jobs;
        try {
            jobs = claim();
        } catch (RuntimeException e) {
            log.warn("Could not claim bulk jobs: {}", e.toString());
            return;
        }
        for (Claimed job : jobs) {
            try {
                feed(job);
                finishIfDone(job);
            } catch (RuntimeException e) {
                log.warn("Bulk job {} tick failed; trying again next tick: {}", job.jobId(), e.toString());
            } finally {
                jdbc.update("UPDATE job SET runner_until = NULL WHERE job_id = ? AND status = 'RUNNING'", job.jobId());
            }
        }
    }

    /** Running jobs no other instance holds, oldest first; held for the lease so two instances don't feed one job. */
    private List<Claimed> claim() {
        return jdbc.query("""
                UPDATE job SET runner_until = ?
                 WHERE job_id IN (SELECT job_id FROM job
                                   WHERE status = 'RUNNING' AND (runner_until IS NULL OR runner_until < now())
                                   ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED)
                RETURNING job_id, customer_id, action, profile
                """, (rs, n) -> new Claimed(rs.getObject("job_id", UUID.class), rs.getString("customer_id"),
                RequestAction.valueOf(rs.getString("action")), rs.getString("profile")),
                Timestamp.from(Instant.now().plus(props.lease())));
    }

    /** Submits queued lines until the job has {@code maxInFlight} requests waiting on the network. */
    private void feed(Claimed job) {
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM line_request WHERE job_id = ? AND status = 'PENDING'",
                Integer.class, job.jobId());
        int room = props.maxInFlight() - (pending == null ? 0 : pending);
        if (room <= 0) {
            return;
        }
        List<String> next = jdbc.queryForList(
                "SELECT iccid FROM job_item WHERE job_id = ? AND status = 'QUEUED' ORDER BY iccid LIMIT ?", String.class,
                job.jobId(), room);
        for (String iccid : next) {
            try {
                RequestService.Accepted accepted = requests.submitForJob(job.customerId(), iccid, job.action(), job.profile(),
                        job.jobId());
                jdbc.update("UPDATE job_item SET status = 'SUBMITTED', request_id = ? WHERE job_id = ? AND iccid = ?",
                        accepted.request().requestId(), job.jobId(), iccid);
            } catch (RequestRejected r) {
                // the line failed a check (not this customer's, wrong state, removable SIM...): it never reaches the network
                jdbc.update("UPDATE job_item SET status = 'REJECTED', reason = ? WHERE job_id = ? AND iccid = ?", r.code,
                        job.jobId(), iccid);
            }
        }
    }

    /** Marks the job done once every line has an outcome, and tells the customer once. */
    private void finishIfDone(Claimed job) {
        Counts c = counts(job.jobId());
        if (c.queued > 0 || c.pending > 0) {
            return;
        }
        Instant now = Instant.now();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", job.jobId().toString());
        details.put("action", job.action().name());
        if (job.profile() != null) {
            details.put("profile", job.profile());
        }
        details.put("succeeded", c.completed);
        details.put("failed", c.failed + c.rejected);
        details.put("rejected", c.rejected);
        CustomerNotice notice = new CustomerNotice(UUID.randomUUID(), job.customerId(), CustomerNotice.Kind.BULK_JOB_SUMMARY,
                now, details);
        Boolean finished = tx.execute(status -> {
            int updated = jdbc.update("""
                    UPDATE job SET status = 'COMPLETED', succeeded = ?, failed = ?, completed_at = ?, runner_until = NULL
                    WHERE job_id = ? AND status = 'RUNNING'
                    """, c.completed, c.failed + c.rejected, Timestamp.from(now), job.jobId());
            if (updated == 0) {
                return false;
            }
            // the same outbox presence-monitor uses for outage notices; its relay re-sends this if the send below fails
            jdbc.update("INSERT INTO customer_notice (notice_id, customer_id, kind, at, details) VALUES (?, ?, ?, ?, ?::jsonb)",
                    notice.noticeId(), notice.customerId(), notice.kind().name(), Timestamp.from(now),
                    Json.write(notice.details()));
            return true;
        });
        if (!Boolean.TRUE.equals(finished)) {
            return;
        }
        log.info("Job {} finished: {} succeeded, {} failed ({} refused before the network)", job.jobId(), c.completed,
                c.failed + c.rejected, c.rejected);
        try {
            kafka.send(Topics.CUSTOMER_NOTICES, notice.customerId(), Json.write(notice)).get(5, TimeUnit.SECONDS);
            jdbc.update("UPDATE customer_notice SET published_at = now() WHERE notice_id = ?", notice.noticeId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Publishing the summary of job {} failed; the notice relay will send it: {}", job.jobId(), e.toString());
        }
    }
}
