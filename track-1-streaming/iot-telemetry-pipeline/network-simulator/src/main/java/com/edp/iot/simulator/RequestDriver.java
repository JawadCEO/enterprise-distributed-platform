package com.edp.iot.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Customer requests during a traffic run (FR-4 to FR-8): suspends, restores, activations and a few
 * deactivations through customer-api, plus faults on demand: the same request sent twice with one
 * {@code Idempotency-Key}, a second request while one is in progress, and actions the line's state
 * does not allow. At the end every request must have finished, and its outcome must match what the
 * simulated network decided.
 */
final class RequestDriver {

    private static final Logger log = LoggerFactory.getLogger(RequestDriver.class);

    /** One accepted request. */
    static final class Tracked {
        final String requestId;
        final SimLine line;
        final String action;
        volatile String status = "PENDING";
        volatile String reason;
        volatile Instant createdAt;
        volatile Instant completedAt;

        Tracked(String requestId, SimLine line, String action) {
            this.requestId = requestId;
            this.line = line;
            this.action = action;
        }
    }

    record Report(int accepted, int completed, int failedRejected, int failedTimeout, int stillPending, int mismatched,
                  int duplicatesMatched, int duplicatesWrong, int conflictsAsExpected, int conflictsWrong,
                  int invalidAsExpected, int invalidWrong, int linesStuckPending, double p50Ms, double p95Ms) {
        boolean clean() {
            return stillPending == 0 && mismatched == 0 && duplicatesWrong == 0 && conflictsWrong == 0
                    && invalidWrong == 0 && linesStuckPending == 0;
        }
    }

    private final SimulatorProperties props;
    private final HttpClient http;
    private final Registrar registrar;
    private final List<SimLine> lines;
    private final Random random;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
    private final Map<SimLine, Tracked> inFlight = new ConcurrentHashMap<>();
    private final LongAdder rateLimited = new LongAdder();
    private final LongAdder unexpected = new LongAdder();
    private final LongAdder stateChangedUnderUs = new LongAdder();
    private final LongAdder duplicatesMatched = new LongAdder();
    private final LongAdder duplicatesWrong = new LongAdder();
    private final LongAdder conflictsAsExpected = new LongAdder();
    private final LongAdder conflictsWrong = new LongAdder();
    private final LongAdder invalidAsExpected = new LongAdder();
    private final LongAdder invalidWrong = new LongAdder();

    RequestDriver(SimulatorProperties props, HttpClient http, Registrar registrar, List<SimLine> lines, long seed) {
        this.props = props;
        this.http = http;
        this.registrar = registrar;
        this.lines = lines;
        this.random = new Random(seed);
    }

    void start() {
        if (props.requestRate() <= 0) {
            return;
        }
        long periodMicros = (long) (1_000_000 / props.requestRate());
        scheduler.scheduleAtFixedRate(this::safeTick, 1_000_000, periodMicros, TimeUnit.MICROSECONDS);
        scheduler.scheduleWithFixedDelay(this::safePoll, 1, 1, TimeUnit.SECONDS);
        log.info("Sending about {} customer requests per second", props.requestRate());
    }

    /** Stops new requests, waits for the accepted ones to finish, then reconciles them with the network. */
    /** Every request the driver made, with the outcome it last read. */
    List<Tracked> tracked() {
        return new ArrayList<>(tracked.values());
    }

    Report finish() throws InterruptedException {
        scheduler.shutdownNow();
        // wait from the newest request's start, not from now: one sent during the drain has its full time limit
        long newest = inFlight.values().stream().map(t -> t.createdAt).filter(java.util.Objects::nonNull)
                .mapToLong(Instant::toEpochMilli).max().orElse(System.currentTimeMillis());
        long deadline = Math.max(System.currentTimeMillis(), newest) + props.requestWait().toMillis();
        while (!inFlight.isEmpty() && System.currentTimeMillis() < deadline) {
            poll();
            if (!inFlight.isEmpty()) {
                log.info("Waiting for {} requests to finish", inFlight.size());
                Thread.sleep(2000);
            }
        }
        poll();
        return reconcile();
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.debug("Request tick failed: {}", e.toString());
        }
    }

    private void safePoll() {
        try {
            poll();
        } catch (RuntimeException e) {
            log.debug("Request poll failed: {}", e.toString());
        }
    }

    private void tick() {
        double fault = random.nextDouble();
        if (fault < props.conflictingRequestRate() && !inFlight.isEmpty()) {
            // a second request on a line that already has one in progress: expect 409 REQUEST_IN_PROGRESS
            Tracked busy = new ArrayList<>(inFlight.values()).get(random.nextInt(inFlight.size()));
            JsonNode body = submitRaw(busy.line, busy.action, UUID.randomUUID().toString());
            if (body != null && body.path("_status").asInt() == 409 && "REQUEST_IN_PROGRESS".equals(body.path("code").asText())) {
                conflictsAsExpected.increment();
            } else if (body != null && body.path("_status").asInt() == 409 && "INVALID_STATE".equals(body.path("code").asText())) {
                stateChangedUnderUs.increment(); // finished meanwhile
            } else if (body != null && body.path("_status").asInt() == 202 && !stillPending(busy)) {
                // the first request finished after our last poll, so this one is legitimate
                stateChangedUnderUs.increment();
                inFlight.remove(busy.line);
                track(busy.line, busy.action, body);
            } else if (body != null && body.path("_status").asInt() != 429) {
                if (body.path("_status").asInt() == 202) {
                    track(busy.line, busy.action, body); // keep following it so the line isn't left unwatched
                }
                conflictsWrong.increment();
                log.warn("Expected REQUEST_IN_PROGRESS for {} on {}, got {}", busy.action, busy.line.iccid, body);
            }
            return;
        }
        SimLine line = lines.get(random.nextInt(lines.size()));
        if (inFlight.containsKey(line) || line.lineState.equals("DEACTIVATED") || line.lineState.equals("PENDING")) {
            return;
        }
        if (fault < props.conflictingRequestRate() + props.invalidRequestRate()) {
            String invalid = invalidAction(line.lineState);
            JsonNode body = submitRaw(line, invalid, UUID.randomUUID().toString());
            if (body != null && body.path("_status").asInt() == 409 && "INVALID_STATE".equals(body.path("code").asText())) {
                invalidAsExpected.increment();
            } else if (body != null && body.path("_status").asInt() == 202) {
                // our view of the line was stale (a SIM move suspended it); track the request it started
                track(line, invalid, body);
            } else if (body != null && body.path("_status").asInt() != 429) {
                invalidWrong.increment();
                log.warn("Expected INVALID_STATE for {} on {} ({}), got {}", invalid, line.iccid, line.lineState, body);
            }
            return;
        }
        String action = validAction(line.lineState);
        String key = UUID.randomUUID().toString();
        JsonNode body = submitRaw(line, action, key);
        if (body == null) {
            return;
        }
        int status = body.path("_status").asInt();
        if (status == 202) {
            Tracked t = track(line, action, body);
            if (random.nextDouble() < props.duplicateRequestRate()) {
                JsonNode again = submitRaw(line, action, key);
                if (again != null && again.path("_status").asInt() == 202 && t.requestId.equals(again.path("requestId").asText())) {
                    duplicatesMatched.increment();
                } else if (again != null && again.path("_status").asInt() != 429) {
                    duplicatesWrong.increment();
                    log.warn("Repeat with the same Idempotency-Key gave {} instead of request {}", again, t.requestId);
                }
            }
        } else if (status == 409 && "INVALID_STATE".equals(body.path("code").asText())) {
            stateChangedUnderUs.increment();
            line.lineState = body.path("lineState").asText(line.lineState);
        } else if (status == 429) {
            rateLimited.increment();
        } else {
            unexpected.increment();
            log.warn("Unexpected answer to {} on {}: {}", action, line.iccid, body);
        }
    }

    private boolean stillPending(Tracked t) {
        JsonNode body = get("/v1/requests/" + t.requestId, t.line.customerId);
        return body == null || "PENDING".equals(body.path("status").asText());
    }

    private Tracked track(SimLine line, String action, JsonNode body) {
        Tracked t = new Tracked(body.path("requestId").asText(), line, action);
        t.createdAt = instant(body, "createdAt");
        tracked.put(t.requestId, t);
        inFlight.put(line, t);
        line.lineState = "PENDING";
        return t;
    }

    private String validAction(String state) {
        return switch (state) {
            case "INVENTORY" -> "activate";
            case "SUSPENDED" -> random.nextDouble() < props.deactivateShare() ? "deactivate" : "restore";
            default -> random.nextDouble() < props.deactivateShare() ? "deactivate" : "suspend";
        };
    }

    private String invalidAction(String state) {
        return switch (state) {
            case "INVENTORY" -> "suspend";
            case "SUSPENDED" -> "activate";
            default -> "restore";
        };
    }

    /** Polls in-flight requests; a finished one updates the line's state as the simulator sees it. */
    private void poll() {
        for (Tracked t : new ArrayList<>(inFlight.values())) {
            JsonNode body = get("/v1/requests/" + t.requestId, t.line.customerId);
            if (body == null) {
                continue;
            }
            t.createdAt = instant(body, "createdAt");
            String status = body.path("status").asText();
            if (status.equals("PENDING")) {
                continue;
            }
            t.status = status;
            t.reason = body.path("reason").asText(null);
            t.completedAt = instant(body, "completedAt");
            JsonNode line = get("/v1/lines/" + t.line.iccid, t.line.customerId);
            if (line != null) {
                t.line.lineState = line.path("lineState").asText(t.line.lineState);
            }
            inFlight.remove(t.line);
        }
    }

    private Report reconcile() {
        int completed = 0, rejected = 0, timedOut = 0, pending = 0, mismatched = 0;
        List<Long> latencies = new ArrayList<>();
        for (Tracked t : tracked.values()) {
            JsonNode decision = getNetwork("/v1/provision/" + t.requestId);
            String network = decision == null ? "NONE" : decision.path("outcome").asText();
            boolean ok;
            switch (t.status) {
                case "COMPLETED" -> {
                    completed++;
                    ok = network.equals("DONE");
                    if (t.createdAt != null && t.completedAt != null) {
                        latencies.add(Duration.between(t.createdAt, t.completedAt).toMillis());
                    }
                }
                case "FAILED" -> {
                    if ("NETWORK_TIMEOUT".equals(t.reason)) {
                        timedOut++;
                        // the network never answered, or never got the command
                        ok = network.equals("DROPPED") || network.equals("NONE");
                    } else {
                        rejected++;
                        ok = network.equals("REJECTED");
                    }
                }
                default -> {
                    pending++;
                    ok = true; // counted as still pending
                }
            }
            if (!ok) {
                mismatched++;
                log.warn("Request {} ({} on {}) ended {} {} but the network says {}", t.requestId, t.action, t.line.iccid,
                        t.status, t.reason, network);
            }
        }
        int stuck = 0;
        for (SimLine line : new ArrayList<>(tracked.values().stream().map(t -> t.line).distinct().toList())) {
            JsonNode body = get("/v1/lines/" + line.iccid, line.customerId);
            if (body != null && "PENDING".equals(body.path("lineState").asText())) {
                stuck++;
                log.warn("Line {} is still PENDING after every request finished", line.iccid);
            }
        }
        latencies.sort(Long::compare);
        double p50 = latencies.isEmpty() ? 0 : latencies.get(latencies.size() / 2);
        double p95 = latencies.isEmpty() ? 0 : latencies.get(Math.min(latencies.size() - 1, (int) (latencies.size() * 0.95)));
        log.info("Requests: {} rate limited, {} found the line's state changed meanwhile, {} unexpected answers",
                rateLimited.sum(), stateChangedUnderUs.sum(), unexpected.sum());
        return new Report(tracked.size(), completed, rejected, timedOut, pending, mismatched, (int) duplicatesMatched.sum(),
                (int) duplicatesWrong.sum(), (int) conflictsAsExpected.sum(), (int) conflictsWrong.sum(),
                (int) invalidAsExpected.sum(), (int) invalidWrong.sum(), stuck, p50, p95);
    }

    /** The response body with its HTTP status added as {@code _status}; null when the call itself failed. */
    private JsonNode submitRaw(SimLine line, String action, String idempotencyKey) {
        HttpRequest request = registrar.request("/v1/lines/" + line.iccid + "/" + action, line.customerId)
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) Jsons.read(
                    response.body().isBlank() ? "{}" : response.body());
            node.put("_status", response.statusCode());
            return node;
        } catch (Exception e) {
            unexpected.increment();
            return null;
        }
    }

    private JsonNode get(String path, String customerId) {
        try {
            HttpResponse<String> response = http.send(registrar.request(path, customerId).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Jsons.read(response.body()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode getNetwork(String path) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(props.networkUrl() + path))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Jsons.read(response.body()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant instant(JsonNode body, String field) {
        String text = body.path(field).asText(null);
        return text == null ? null : Instant.parse(text);
    }
}
