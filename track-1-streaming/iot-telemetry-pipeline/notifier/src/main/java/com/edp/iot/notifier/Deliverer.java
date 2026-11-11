package com.edp.iot.notifier;

import com.edp.iot.common.Json;
import com.edp.iot.common.notify.WebhookSignature;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends pending notifications (FR-23, FR-24, ADR-008).
 *
 * <ul>
 *   <li><b>Isolation:</b> each endpoint has at most {@code maxInFlightPerWebhook} requests in
 *       flight, sent asynchronously with a timeout, so a slow or dead endpoint holds a few slots and
 *       no threads, and other customers' notifications keep flowing.</li>
 *   <li><b>Back-off:</b> a failed notification is retried with exponential back-off and jitter.
 *       An endpoint that keeps failing is also paused as a whole, so a dead endpoint costs one
 *       probe per back-off step rather than one request per notification.</li>
 *   <li><b>Parking:</b> a notification still failing after {@code retryFor} is parked. It stays
 *       readable, and can be sent again, through customer-api.</li>
 *   <li><b>Order per line:</b> a notification is claimed only when no earlier one for the same
 *       line and endpoint is still pending (NFR-10a).</li>
 *   <li><b>At least once:</b> a claim is a lease. If this instance dies mid-send, the lease runs
 *       out and the notification is sent again with the same {@code notificationId}.</li>
 * </ul>
 */
@Component
class Deliverer {

    private static final Logger log = LoggerFactory.getLogger(Deliverer.class);

    private static final String DUE_WEBHOOKS = """
            SELECT DISTINCT webhook_id FROM notification
            WHERE status = 'PENDING' AND next_attempt_at <= now() AND (lease_until IS NULL OR lease_until < now())
            LIMIT 10000
            """;

    private static final String CLAIM = """
            WITH due AS (
                SELECT n.notification_id
                FROM notification n
                WHERE n.webhook_id = ? AND n.status = 'PENDING' AND n.next_attempt_at <= now()
                  AND (n.lease_until IS NULL OR n.lease_until < now())
                  AND NOT EXISTS (SELECT 1 FROM notification p
                                  WHERE p.webhook_id = n.webhook_id AND p.ordering_key = n.ordering_key
                                    AND p.status = 'PENDING' AND p.seq < n.seq)
                ORDER BY n.seq
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE notification t SET lease_until = now() + (? * interval '1 millisecond')
            FROM due WHERE t.notification_id = due.notification_id
            RETURNING t.notification_id, t.payload, t.attempts, t.retry_since
            """;

    record Claimed(UUID notificationId, String payload, int attempts, Instant retrySince) {
    }

    private final JdbcTemplate jdbc;
    private final HttpClient http;
    private final WebhookDirectory webhooks;
    private final NotifierProperties props;
    private final Backoff backoff;
    private final Semaphore globalSlots;
    private final Map<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Map<UUID, EndpointHealth> health = new ConcurrentHashMap<>();
    private final Object signal = new Object();
    private final Counter delivered;
    private final Counter failed;
    private final Counter parked;
    private final Timer latency;
    private volatile boolean running = true;
    private Thread loop;

    /**
     * Consecutive failed rounds of one endpoint, and when it may be tried again. The pause grows
     * once per round, not once per notification, so an endpoint that comes back is found again
     * within one back-off step.
     */
    private static final class EndpointHealth {
        int consecutiveFailures;
        volatile long pausedUntil;
    }

    Deliverer(JdbcTemplate jdbc, HttpClient webhookHttpClient, WebhookDirectory webhooks, NotifierProperties props,
              MeterRegistry meters) {
        this.jdbc = jdbc;
        this.http = webhookHttpClient;
        this.webhooks = webhooks;
        this.props = props;
        this.backoff = new Backoff(props.retryInitial(), props.retryMax());
        this.globalSlots = new Semaphore(props.maxInFlight());
        this.delivered = meters.counter("iot.notifier.delivered");
        this.failed = meters.counter("iot.notifier.attempts.failed");
        this.parked = meters.counter("iot.notifier.parked");
        this.latency = Timer.builder("iot.notifier.latency")
                .description("From the change to the webhook being accepted by the endpoint (NFR-2a)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
    }

    @PostConstruct
    void start() {
        loop = new Thread(this::run, "notifier-deliverer");
        loop.setDaemon(true);
        loop.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        wake();
    }

    /** New notifications were stored; look for work now instead of at the next poll. */
    void wake() {
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    private void run() {
        while (running) {
            try {
                dispatchDue();
            } catch (RuntimeException e) {
                log.warn("Delivery round failed: {}", e.toString());
            }
            synchronized (signal) {
                try {
                    signal.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void dispatchDue() {
        List<UUID> due = jdbc.queryForList(DUE_WEBHOOKS, UUID.class);
        long now = System.currentTimeMillis();
        for (UUID webhookId : due) {
            EndpointHealth h = health.get(webhookId);
            if (h != null && h.pausedUntil > now) {
                continue;
            }
            WebhookDirectory.Endpoint endpoint = webhooks.active(webhookId);
            if (endpoint == null) {
                continue; // removed: its pending notifications are parked by customer-api or the sweep
            }
            AtomicInteger flying = inFlight.computeIfAbsent(webhookId, k -> new AtomicInteger());
            int free = props.maxInFlightPerWebhook() - flying.get();
            if (free <= 0) {
                continue;
            }
            List<Claimed> claimed = jdbc.query(CLAIM, (rs, n) -> new Claimed(rs.getObject("notification_id", UUID.class),
                    rs.getString("payload"), rs.getInt("attempts"), rs.getTimestamp("retry_since").toInstant()),
                    webhookId, free, props.lease().toMillis());
            for (Claimed c : claimed) {
                if (!globalSlots.tryAcquire()) {
                    release(c.notificationId()); // this instance is full; leave it for the next round
                    continue;
                }
                flying.incrementAndGet();
                send(endpoint, c, flying);
            }
        }
    }

    private void send(WebhookDirectory.Endpoint endpoint, Claimed c, AtomicInteger flying) {
        long timestamp = Instant.now().getEpochSecond();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint.url()))
                    .timeout(props.callTimeout())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "iot-platform-notifier/1")
                    .header(WebhookSignature.ID_HEADER, c.notificationId().toString())
                    .header(WebhookSignature.TIMESTAMP_HEADER, String.valueOf(timestamp))
                    .header(WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(endpoint.secret(), timestamp, c.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(c.payload()))
                    .build();
        } catch (IllegalArgumentException e) {
            finished(endpoint, c, flying, false, "INVALID_URL");
            return;
        }
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            int code = response == null ? 0 : response.statusCode();
            boolean ok = error == null && code >= 200 && code < 300;
            String detail = error != null ? rootName(error) : "HTTP " + code;
            finished(endpoint, c, flying, ok, ok ? null : detail);
        });
    }

    private void finished(WebhookDirectory.Endpoint endpoint, Claimed c, AtomicInteger flying, boolean ok, String error) {
        try {
            if (ok) {
                jdbc.update("""
                        UPDATE notification
                           SET status = 'DELIVERED', delivered_at = now(), last_attempt_at = now(), attempts = attempts + 1,
                               lease_until = NULL, last_error = NULL
                         WHERE notification_id = ? AND status = 'PENDING'
                        """, c.notificationId());
                delivered.increment();
                recordLatency(c.payload());
                healthy(endpoint.webhookId());
            } else {
                failed.increment();
                int failures = c.attempts() + 1;
                Instant next = Instant.now().plus(backoff.after(failures));
                boolean park = !next.isBefore(c.retrySince().plus(props.retryFor()));
                if (park) {
                    jdbc.update("""
                            UPDATE notification
                               SET status = 'PARKED', parked_at = now(), last_attempt_at = now(), attempts = attempts + 1,
                                   lease_until = NULL, last_error = ?
                             WHERE notification_id = ? AND status = 'PENDING'
                            """, error, c.notificationId());
                    parked.increment();
                } else {
                    jdbc.update("""
                            UPDATE notification
                               SET next_attempt_at = ?, last_attempt_at = now(), attempts = attempts + 1, lease_until = NULL,
                                   last_error = ?
                             WHERE notification_id = ? AND status = 'PENDING'
                            """, Timestamp.from(next), error, c.notificationId());
                }
                unhealthy(endpoint.webhookId());
            }
        } catch (RuntimeException e) {
            log.warn("Could not record the outcome of notification {}; its lease will run out and it is sent again: {}",
                    c.notificationId(), e.toString());
        } finally {
            flying.decrementAndGet();
            globalSlots.release();
            wake();
        }
    }

    private void release(UUID notificationId) {
        jdbc.update("UPDATE notification SET lease_until = NULL WHERE notification_id = ?", notificationId);
    }

    private void healthy(UUID webhookId) {
        health.remove(webhookId);
    }

    private void unhealthy(UUID webhookId) {
        EndpointHealth h = health.computeIfAbsent(webhookId, k -> new EndpointHealth());
        synchronized (h) {
            long now = System.currentTimeMillis();
            if (now < h.pausedUntil) {
                return; // another request of the same round failed first; the pause already covers it
            }
            h.consecutiveFailures++;
            h.pausedUntil = now + backoff.after(h.consecutiveFailures).toMillis();
        }
    }

    private void recordLatency(String payload) {
        try {
            Object at = Json.readMap(payload).get("at");
            if (at instanceof String s) {
                long ms = Duration.between(Instant.parse(s), Instant.now()).toMillis();
                if (ms >= 0) {
                    latency.record(ms, TimeUnit.MILLISECONDS);
                }
            }
        } catch (RuntimeException ignored) {
            // latency is informational only
        }
    }

    private static String rootName(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
