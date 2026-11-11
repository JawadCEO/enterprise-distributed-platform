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
    private final long outageStartsAt;
    /** Lines that were connected when their region went silent. */
    private final Set<String> outageLines = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final long outageEndsAt;

    TrafficEngine(SimulatorProperties props, HttpClient http, Registrar registrar, String networkKey) {
        this.props = props;
        this.scenario = Scenario.of(props);
        this.http = http;
        this.networkKey = networkKey;
        this.ingestUrls = props.ingestUrl().split(",");
        this.random = new Random(props.seed());
        this.concurrency = new Semaphore(props.maxConcurrentRequests());
        this.keepAliveWindowMs = registrar.keepAliveSeconds() * 1000L;
        int tickMillis = (int) (props.tickSeconds() * 1000 * scenario.tickFactor());
        long now = System.currentTimeMillis();
        this.outageStartsAt = scenario.regionOutage() ? now + props.outageStart().toMillis() : Long.MAX_VALUE;
        this.outageEndsAt = scenario.regionOutage() ? outageStartsAt + props.outageLength().toMillis() : Long.MAX_VALUE;
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

    /** The outage window as wall-clock times, or null when this scenario has none. */
    long[] outageWindow() {
        return scenario.regionOutage() ? new long[]{outageStartsAt, outageEndsAt} : null;
    }

    /** Lines that were connected when their region went silent: none of them should be alerted for it. */
    Set<String> outageLines() {
        return outageLines;
    }

    private boolean inOutage(SimLine line, long now) {
        return now >= outageStartsAt && now < outageEndsAt && line.region.equals(props.outageRegion());
    }

    Stats run() throws Exception {
        log.info("Scenario {}: {} lines, {} customers, tick {}s, duration {}", scenario.name(), props.lines(),
                props.customers(), props.tickSeconds() * scenario.tickFactor(), props.duration());
        if (scenario.regionOutage()) {
            log.info("Region {} goes silent {} after the start, for {}", props.outageRegion(), props.outageStart(),
                    props.outageLength());
        }
        long start = System.currentTimeMillis();
        long end = start + props.duration().toMillis();
        scheduler.scheduleAtFixedRate(() -> log.info(stats.line((System.currentTimeMillis() - start) / 1000.0)),
                5, 5, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::flushLedger, 1, 1, TimeUnit.SECONDS);
        if (props.gatewayBatch() > 100) {
            throw new IllegalArgumentException("GATEWAY_BATCH must be at most 100, event-ingest's limit per request");
        }
        if (props.gatewayBatch() > 0) {
            log.info("Gateway mode: up to {} events from many lines per request", props.gatewayBatch());
            scheduler.scheduleWithFixedDelay(this::sendReady, 10, 10, TimeUnit.MILLISECONDS);
        }

        boolean[] outageLogged = {false, false};
        while (System.currentTimeMillis() < end) {
            long now = System.currentTimeMillis();
            while (!due.isEmpty() && due.peek().nextDueAt <= now) {
                SimLine line = due.poll();
                tick(line, now);
                due.add(line);
            }
            if (!outageLogged[0] && now >= outageStartsAt) {
                outageLogged[0] = true;
                log.info("Region {} is now silent", props.outageRegion());
            }
            if (!outageLogged[1] && now >= outageEndsAt) {
                outageLogged[1] = true;
                log.info("Region {} is back; its lines reconnect", props.outageRegion());
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
            if (now < line.silentUntil || !line.live()) {
                return; // quiet on purpose, or a SIM in inventory or deactivated
            }
            if (inOutage(line, now)) {
                // the region's cells are down: the device can't reach the network, then re-attaches
                if (line.attached) {
                    outageLines.add(line.iccid);
                }
                line.attached = false;
                line.detachedUntil = outageEndsAt;
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
        if (props.gatewayBatch() > 0) {
            ready(line);
            return;
        }
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
        int region = currentIngest.get();
        HttpRequest request = HttpRequest.newBuilder(URI.create(ingestUrl(region) + "/v1/events"))
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
                    onResponse(line, order, response, error, region);
                });
    }

    private void onResponse(SimLine line, List<SimLine.Event> sent, HttpResponse<String> response, Throwable error, int region) {
        JsonNode results = error != null || response == null ? null : parseResults(response.body());
        requestOutcome(region, results == null);
        onResults(line, sent, results);
    }

    /** Applies one line's results; {@code results} is null when the request failed as a whole. */
    private void onResults(SimLine line, List<SimLine.Event> sent, JsonNode results) {
        long retryAfterMs = 0;
        boolean failed = false;
        synchronized (line) {
            line.inFlight = false;
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

    // ---- failover across regions (part 5) ----

    /** INGEST_URL may list several event-ingest addresses, one per region; the first is used until it fails. */
    private final String[] ingestUrls;
    private final java.util.concurrent.atomic.AtomicInteger currentIngest = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger failuresInARow = new java.util.concurrent.atomic.AtomicInteger();
    /** Whole requests failing in a row before the next region's event-ingest is tried. */
    private static final int FAILOVER_AFTER = 20;

    private String ingestUrl(int region) {
        return ingestUrls[region % ingestUrls.length];
    }

    /**
     * Counts whole-request failures against the region the request went to. Answers from a region the
     * simulator already left are ignored, so requests still in flight to a dead region don't send it
     * straight back there.
     */
    private void requestOutcome(int region, boolean failed) {
        if (region != currentIngest.get()) {
            return;
        }
        if (!failed) {
            failuresInARow.set(0);
            return;
        }
        if (ingestUrls.length > 1 && failuresInARow.incrementAndGet() >= FAILOVER_AFTER
                && currentIngest.compareAndSet(region, region + 1)) {
            failuresInARow.set(0);
            log.warn("event-ingest at {} keeps failing; failing over to {}", ingestUrl(region), ingestUrl(region + 1));
        }
    }

    // ---- gateway mode: many lines per request (load benchmark) ----

    private final ConcurrentLinkedQueue<SimLine> readyLines = new ConcurrentLinkedQueue<>();
    private final Set<SimLine> queued = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void ready(SimLine line) {
        if (queued.add(line)) {
            readyLines.add(line);
        }
    }

    /** Packs the events of ready lines into requests of up to {@code gatewayBatch} events and sends them. */
    private void sendReady() {
        try {
            List<SimLine> group = new ArrayList<>();
            List<List<SimLine.Event>> parts = new ArrayList<>();
            int events = 0;
            int lines = readyLines.size();
            for (int i = 0; i < lines; i++) {
                SimLine line = readyLines.poll();
                if (line == null) {
                    break;
                }
                queued.remove(line);
                synchronized (line) {
                    long now = System.currentTimeMillis();
                    if (line.inFlight || line.buffer.isEmpty()) {
                        continue;
                    }
                    if (now < line.backoffUntil) {
                        scheduleSend(line, line.backoffUntil - now);
                        continue;
                    }
                    int take = Math.min(line.buffer.size(), Math.min(props.maxBatch(), props.gatewayBatch() - events));
                    if (take <= 0) {
                        ready(line);
                        continue;
                    }
                    parts.add(new ArrayList<>(new ArrayList<>(line.buffer).subList(0, take)));
                    line.inFlight = true;
                    group.add(line);
                    events += take;
                }
                if (events >= props.gatewayBatch()) {
                    sendGroup(group, parts);
                    group = new ArrayList<>();
                    parts = new ArrayList<>();
                    events = 0;
                }
            }
            if (!group.isEmpty()) {
                sendGroup(group, parts);
            }
        } catch (RuntimeException e) {
            log.warn("Gateway send round failed: {}", e.toString());
        }
    }

    private void sendGroup(List<SimLine> group, List<List<SimLine.Event>> parts) {
        StringBuilder body = new StringBuilder("{\"events\":[");
        boolean first = true;
        for (List<SimLine.Event> part : parts) {
            for (SimLine.Event e : part) {
                if (!first) {
                    body.append(',');
                }
                body.append(e.json());
                first = false;
            }
        }
        body.append("]}");
        int region = currentIngest.get();
        HttpRequest request = HttpRequest.newBuilder(URI.create(ingestUrl(region) + "/v1/events"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "ApiKey " + networkKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        concurrency.acquireUninterruptibly();
        long sentAt = System.nanoTime();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    concurrency.release();
                    stats.recordLatency((System.nanoTime() - sentAt) / 1000);
                    JsonNode results = error != null || response == null ? null : parseResults(response.body());
                    requestOutcome(region, results == null);
                    int offset = 0;
                    for (int i = 0; i < group.size(); i++) {
                        List<SimLine.Event> part = parts.get(i);
                        JsonNode slice = null;
                        if (results != null && results.size() >= offset + part.size()) {
                            com.fasterxml.jackson.databind.node.ArrayNode a = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
                            for (int j = 0; j < part.size(); j++) {
                                a.add(results.get(offset + j));
                            }
                            slice = a;
                        }
                        offset += part.size();
                        onResults(group.get(i), part, slice);
                    }
                });
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
