package com.edp.iot.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Drives the lines: one scheduler thread generates events when each line is due, and sends run
 * asynchronously, at most one request in flight per line so its events reach event-ingest in order
 * (except where out-of-order delivery is injected on purpose). Acknowledged usage goes into the
 * {@link UsageLedger}; every acknowledged event goes into the ledger file.
 */
final class TrafficEngine {

    private static final Logger log = LoggerFactory.getLogger(TrafficEngine.class);
    private static final long PRESENCE_GRACE_MS = 30_000;

    private final SimulatorProperties props;
    private final Scenario scenario;
    private final HttpClient http;
    private final String networkKey;
    private final Stats stats = new Stats();
    private final UsageLedger usage = new UsageLedger();
    private final Random random;
    private final List<SimLine> lines = new ArrayList<>();
    private final PriorityQueue<SimLine> due = new PriorityQueue<>((a, b) -> Long.compare(a.nextDueAt, b.nextDueAt));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Semaphore concurrency;
    private final ConcurrentLinkedQueue<String> ledgerFile = new ConcurrentLinkedQueue<>();
    private final long keepAliveWindowMs;
    private volatile boolean generating = true;

    TrafficEngine(SimulatorProperties props, HttpClient http, Registrar registrar, String networkKey) {
        this.props = props;
        this.scenario = Scenario.of(props);
        this.http = http;
        this.networkKey = networkKey;
        this.random = new Random(props.seed());
        this.concurrency = new Semaphore(props.maxConcurrentRequests());
        this.keepAliveWindowMs = registrar.keepAliveSeconds() * 1000L;
        int tickMillis = (int) (props.tickSeconds() * 1000 * scenario.tickFactor());
        long now = System.currentTimeMillis();
        for (int i = 0; i < props.lines(); i++) {
            int customer = i % props.customers();
            boolean noisy = customer == 0 && scenario.noisyCustomerMultiplier() > 1;
            SimLine line = new SimLine(i, Registrar.customerId(customer), tickMillis, noisy, random);
            line.nextDueAt = now + random.nextInt(Math.max(1, tickMillis));
            lines.add(line);
            due.add(line);
        }
    }

    List<SimLine> lines() {
        return lines;
    }

    UsageLedger usage() {
        return usage;
    }

    Stats run() throws Exception {
        log.info("Scenario {}: {} lines, {} customers, tick {}s, duration {}", scenario.name(), props.lines(),
                props.customers(), props.tickSeconds() * scenario.tickFactor(), props.duration());
        long start = System.currentTimeMillis();
        long end = start + props.duration().toMillis();
        scheduler.scheduleAtFixedRate(() -> log.info(stats.line((System.currentTimeMillis() - start) / 1000.0)),
                5, 5, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::flushLedger, 1, 1, TimeUnit.SECONDS);

        while (System.currentTimeMillis() < end) {
            long now = System.currentTimeMillis();
            while (!due.isEmpty() && due.peek().nextDueAt <= now) {
                SimLine line = due.poll();
                tick(line, now);
                due.add(line);
            }
            Thread.sleep(5);
        }

        generating = false;
        long drainUntil = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < drainUntil && lines.stream().anyMatch(this::pending)) {
            Thread.sleep(200);
        }
        scheduler.shutdownNow();
        flushLedger();
        log.info("Finished. {}", stats.line((System.currentTimeMillis() - start) / 1000.0));
        log.info("Rejections by code: {}", stats.rejectCodes());
        log.info("Usage acknowledged: {} records, {} bytes", usage.records(), usage.totalBytes());
        long unacked = lines.stream().mapToLong(l -> {
            synchronized (l) {
                return l.buffer.size();
            }
        }).sum();
        log.info("Events still unacknowledged at exit: {}", unacked);
        return stats;
    }

    /** Lines that still hold a usage record nobody acknowledged: the platform may have stored it or not. */
    Set<String> linesWithUnackedUsage() {
        Set<String> out = new HashSet<>();
        for (SimLine l : lines) {
            synchronized (l) {
                if (l.buffer.stream().anyMatch(e -> e.usageBytes() > 0)) {
                    out.add(l.iccid);
                }
            }
        }
        return out;
    }

    private boolean pending(SimLine l) {
        synchronized (l) {
            return !l.buffer.isEmpty();
        }
    }

    private void tick(SimLine line, long now) {
        synchronized (line) {
            line.nextDueAt = now + line.tickMillis + random.nextInt(Math.max(1, line.tickMillis / 10));
            if (now < line.silentUntil) {
                return;
            }
            if (!line.attached) {
                if (now >= line.detachedUntil) {
                    line.buffer.addLast(line.attach(now));
                    stats.generated.increment();
                }
            } else if (random.nextDouble() < scenario.silenceRate()) {
                // quiet for longer than the keep-alive window plus grace, so presence marks it offline
                line.silentUntil = now + 2 * keepAliveWindowMs + PRESENCE_GRACE_MS;
                stats.silences.increment();
                return;
            } else if (random.nextDouble() < scenario.detachRate()) {
                line.buffer.addLast(line.detach(now, now + (2L + random.nextInt(4)) * line.tickMillis));
                stats.generated.increment();
                stats.detaches.increment();
            } else {
                if (random.nextDouble() < scenario.simMoveRate()) {
                    line.buffer.addLast(line.moveSim(now, random));
                    stats.generated.increment();
                    stats.simMoves.increment();
                }
                if (random.nextDouble() < scenario.rebootRate()) {
                    line.reboot();
                    stats.reboots.increment();
                }
                int count = line.noisy ? scenario.noisyCustomerMultiplier() : 1;
                for (int i = 0; i < count; i++) {
                    double roll = random.nextDouble();
                    SimLine.Event event = roll < props.usageRate() ? line.usage(now, random)
                            : line.deviceEvent(random, now, roll < props.usageRate() + props.statusRate(),
                            props.clockSkewRate() * scenario.faultFactor(), scenario.faultFactor());
                    line.buffer.addLast(event);
                    stats.generated.increment();
                }
            }
        }
        trySend(line);
    }

    private void trySend(SimLine line) {
        List<SimLine.Event> batch;
        SimLine.Event lastAcked;
        String malformed = null;
        synchronized (line) {
            long now = System.currentTimeMillis();
            if (line.inFlight || line.buffer.isEmpty()) {
                return;
            }
            if (now < line.backoffUntil) {
                scheduleSend(line, line.backoffUntil - now);
                return;
            }
            batch = new ArrayList<>(new ArrayList<>(line.buffer).subList(0, Math.min(props.maxBatch() - 3, line.buffer.size())));
            lastAcked = line.lastAcked;
            line.inFlight = true;
            if (random.nextDouble() < props.malformedRate() * scenario.faultFactor()) {
                malformed = line.malformed(now);
            }
        }
        double faults = scenario.faultFactor();
        List<SimLine.Event> order = new ArrayList<>(batch);
        if (order.size() > 1 && random.nextDouble() < props.outOfOrderRate() * faults) {
            Collections.reverse(order);
        }
        List<String> payload = new ArrayList<>();
        for (SimLine.Event e : order) {
            payload.add(e.json());
        }
        if (lastAcked != null && random.nextDouble() < props.duplicateRate() * faults) {
            payload.add(lastAcked.json());
            stats.duplicatesSent.increment();
        }
        if (malformed != null) {
            payload.add(malformed);
            stats.malformedSent.increment();
        }
        if (random.nextDouble() < props.unknownLineRate() * faults) {
            payload.add(unknownLineEvent());
            stats.unknownLineSent.increment();
        }

        String body = "{\"events\":[" + String.join(",", payload) + "]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.ingestUrl() + "/v1/events"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "ApiKey " + networkKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        concurrency.acquireUninterruptibly();
        long sentAt = System.nanoTime();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    concurrency.release();
                    stats.recordLatency((System.nanoTime() - sentAt) / 1000);
                    onResponse(line, order, response, error);
                });
    }

    private void onResponse(SimLine line, List<SimLine.Event> sent, HttpResponse<String> response, Throwable error) {
        long retryAfterMs = 0;
        boolean failed = false;
        synchronized (line) {
            line.inFlight = false;
            JsonNode results = error != null || response == null ? null : parseResults(response.body());
            if (results == null) {
                stats.requestErrors.increment();
                failed = true;
            } else {
                for (int i = 0; i < results.size(); i++) {
                    JsonNode result = results.get(i);
                    String status = result.path("status").asText();
                    if (i >= sent.size()) {
                        // an injected extra (duplicate, malformed, unknown line): nothing to remove from the buffer
                        if ("REJECTED".equals(status)) {
                            stats.rejected(result.path("code").asText());
                        }
                        continue;
                    }
                    SimLine.Event event = sent.get(i);
                    switch (status) {
                        case "ACCEPTED" -> {
                            line.buffer.remove(event);
                            line.lastAcked = event;
                            stats.accepted.increment();
                            if (event.usageBytes() > 0) {
                                usage.acked(line.iccid, event.eventId(), event.usageBytes());
                            }
                            ledgerFile.add(line.iccid + "," + event.eventId() + "," + event.type() + "," + event.usageBytes()
                                    + "," + System.currentTimeMillis());
                        }
                        case "REJECTED" -> {
                            line.buffer.remove(event);
                            stats.rejected(result.path("code").asText());
                        }
                        case "RATE_LIMITED" -> {
                            stats.rateLimited.increment();
                            retryAfterMs = Math.max(retryAfterMs, result.path("retryAfterMs").asLong(1000));
                        }
                        default -> {
                            stats.retried.increment();
                            retryAfterMs = Math.max(retryAfterMs, result.path("retryAfterMs").asLong(1000));
                        }
                    }
                }
            }
            if (failed) {
                line.consecutiveFailures++;
                retryAfterMs = Math.min(30_000, 250L << Math.min(7, line.consecutiveFailures));
            } else {
                line.consecutiveFailures = 0;
            }
            line.backoffUntil = System.currentTimeMillis() + retryAfterMs;
        }
        if (pending(line) && (generating || retryAfterMs < 30_000)) {
            scheduleSend(line, retryAfterMs);
        }
    }

    private void scheduleSend(SimLine line, long delayMs) {
        try {
            scheduler.schedule(() -> trySend(line), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // scheduler shut down at the end of the run
        }
    }

    private static JsonNode parseResults(String body) {
        try {
            JsonNode results = Jsons.read(body).path("results");
            return results.isArray() ? results : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A line the platform has never heard of; event-ingest must reject it as UNKNOWN_LINE (FR-13). */
    private String unknownLineEvent() {
        return Jsons.write(Map.of("eventId", UUID.randomUUID().toString(), "type", "KEEP_ALIVE",
                "lineId", String.format("89999%014d", random.nextInt(1_000_000)), "imei", "359999999999999",
                "bootId", 1, "sequence", 0, "deviceTime", Instant.now().toString(), "payload", Map.of()));
    }

    private void flushLedger() {
        if (ledgerFile.isEmpty()) {
            return;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(Path.of(props.ledgerFile()), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            String line;
            while ((line = ledgerFile.poll()) != null) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            log.warn("Could not write ledger: {}", e.toString());
        }
    }
}
