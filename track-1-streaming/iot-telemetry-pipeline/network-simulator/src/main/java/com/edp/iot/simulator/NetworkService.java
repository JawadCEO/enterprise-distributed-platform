package com.edp.iot.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The simulated carrier network's provisioning interface, for provisioning-worker:
 * <ul>
 *   <li>{@code POST /v1/provision} takes a command and answers {@code 202} at once, or {@code 503}
 *       now and then so the worker has to retry. A repeat of the same request ID is answered the
 *       same way and changes nothing.</li>
 *   <li>About a second later the outcome goes back to event-ingest as a {@code PROVISIONING_RESULT}:
 *       mostly {@code DONE}, sometimes {@code REJECTED}, and a few commands are never answered, so
 *       the platform's time limit has to fail them.</li>
 *   <li>{@code GET /v1/provision/{requestId}} returns what the network decided, which the traffic
 *       run uses to reconcile the platform's request outcomes.</li>
 *   <li>A {@code SWITCH_PROFILE} (Flow E) makes the device drop off the network and come back while
 *       it enables the new profile, so the platform sees a detach and an attach before the result.
 *       A rejected switch reports {@code PROFILE_FAILED}: the device fell back to its old profile.</li>
 * </ul>
 */
final class NetworkService {

    private static final Logger log = LoggerFactory.getLogger(NetworkService.class);

    enum Outcome { DONE, REJECTED, DROPPED }

    private final SimulatorProperties props;
    private final HttpClient http;
    private final Registrar registrar;
    private final Random random = new Random();
    private final Map<String, Entry> decisions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private volatile String networkKey;

    private static final class Entry {
        final String requestId;
        final String iccid;
        final String action;
        final Outcome outcome;
        final Instant decidedAt;
        volatile Instant reportedAt;

        Entry(String requestId, String iccid, String action, Outcome outcome, Instant decidedAt) {
            this.requestId = requestId;
            this.iccid = iccid;
            this.action = action;
            this.outcome = outcome;
            this.decidedAt = decidedAt;
        }
    }

    NetworkService(SimulatorProperties props, HttpClient http, Registrar registrar) {
        this.props = props;
        this.http = http;
        this.registrar = registrar;
    }

    void run() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(props.networkPort()), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/v1/provision", this::handle);
        server.createContext("/health", ex -> respond(ex, 200, "{\"status\":\"UP\"}"));
        server.start();
        log.info("Simulated network listening on {}: latency {}, reject {}, unavailable {}, drop {}", props.networkPort(),
                props.networkLatency(), props.networkRejectRate(), props.networkUnavailableRate(), props.networkDropRate());
        networkKey = obtainNetworkKey();
        new CountDownLatch(1).await(); // until the container stops
    }

    private String obtainNetworkKey() throws InterruptedException {
        while (true) {
            try {
                String key = registrar.networkKey();
                log.info("Network key ready for reporting provisioning results");
                return key;
            } catch (Exception e) {
                log.info("Waiting for customer-api to issue a network key: {}", e.toString());
                Thread.sleep(3000);
            }
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (method.equals("GET") && path.startsWith("/v1/provision/")) {
                Entry e = decisions.get(path.substring("/v1/provision/".length()));
                if (e == null) {
                    respond(ex, 404, "{\"code\":\"NOT_FOUND\"}");
                } else {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("requestId", e.requestId);
                    body.put("outcome", e.outcome.name());
                    body.put("decidedAt", e.decidedAt.toString());
                    body.put("reportedAt", e.reportedAt == null ? null : e.reportedAt.toString());
                    respond(ex, 200, Jsons.write(body));
                }
                return;
            }
            if (!method.equals("POST") || !path.equals("/v1/provision")) {
                respond(ex, 404, "{\"code\":\"NOT_FOUND\"}");
                return;
            }
            JsonNode command = Jsons.read(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String requestId = command.path("requestId").asText("");
            String iccid = command.path("iccid").asText("");
            String action = command.path("action").asText("");
            if (requestId.isEmpty() || iccid.isEmpty() || action.isEmpty()) {
                respond(ex, 400, "{\"code\":\"BAD_COMMAND\"}");
                return;
            }
            if (networkKey == null || (!decisions.containsKey(requestId) && random.nextDouble() < props.networkUnavailableRate())) {
                respond(ex, 503, "{\"code\":\"BUSY\"}");
                return;
            }
            decisions.computeIfAbsent(requestId, id -> {
                double roll = random.nextDouble();
                Outcome outcome = roll < props.networkDropRate() ? Outcome.DROPPED
                        : roll < props.networkDropRate() + props.networkRejectRate() ? Outcome.REJECTED : Outcome.DONE;
                Entry e = new Entry(id, iccid, action, outcome, Instant.now());
                if (outcome != Outcome.DROPPED) {
                    long latency = (long) (props.networkLatency().toMillis() * (0.5 + random.nextDouble()));
                    if (action.equals("SWITCH_PROFILE")) {
                        scheduler.schedule(() -> reconnect(e, 0), latency / 2, TimeUnit.MILLISECONDS);
                    }
                    scheduler.schedule(() -> report(e, 0), latency, TimeUnit.MILLISECONDS);
                }
                return e;
            });
            respond(ex, 202, "{\"accepted\":true}");
        } catch (RuntimeException e) {
            respond(ex, 400, "{\"code\":\"BAD_COMMAND\"}");
        }
    }

    /** Sends the outcome to event-ingest, retrying until it is acknowledged (for up to 10 minutes). */
    private void report(Entry e, int attempt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", e.requestId);
        payload.put("outcome", e.outcome.name());
        if (e.outcome == Outcome.REJECTED) {
            payload.put("reason", e.action.equals("SWITCH_PROFILE") ? "PROFILE_FAILED" : "NETWORK_REJECTED");
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.nameUUIDFromBytes(("result:" + e.requestId).getBytes(StandardCharsets.UTF_8)).toString());
        event.put("type", "PROVISIONING_RESULT");
        event.put("lineId", e.iccid);
        event.put("networkTime", Instant.now().toString());
        event.put("payload", payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.ingestUrl() + "/v1/events"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "ApiKey " + networkKey)
                .POST(HttpRequest.BodyPublishers.ofString("{\"events\":[" + Jsons.write(event) + "]}"))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            String status = error != null || response == null ? "ERROR"
                    : Jsons.read(response.body()).path("results").path(0).path("status").asText("ERROR");
            if (status.equals("ACCEPTED") || status.equals("REJECTED")) {
                e.reportedAt = Instant.now();
                if (status.equals("REJECTED")) {
                    log.warn("event-ingest rejected the result for {}: {}", e.requestId, response.body());
                }
            } else if (attempt < 300) {
                scheduler.schedule(() -> report(e, attempt + 1), Math.min(2000, 200L << Math.min(attempt, 4)),
                        TimeUnit.MILLISECONDS);
            } else {
                log.error("Gave up reporting the result for {}", e.requestId);
            }
        });
    }

    /** The device detaching and re-attaching while it switches profile, sent before the result. */
    private void reconnect(Entry e, int attempt) {
        Instant now = Instant.now();
        List<String> events = new java.util.ArrayList<>();
        for (String type : List.of("DEVICE_DETACHED", "DEVICE_ATTACHED")) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventId", UUID.nameUUIDFromBytes((type + ":" + e.requestId).getBytes(StandardCharsets.UTF_8)).toString());
            event.put("type", type);
            event.put("lineId", e.iccid);
            // the network sees the device's IMEI when it attaches; simulated lines number their devices like their SIMs
            event.put("imei", Registrar.imei(Integer.parseInt(e.iccid.substring(5)) - 1));
            event.put("networkTime", (type.equals("DEVICE_DETACHED") ? now : now.plusMillis(1)).toString());
            events.add(Jsons.write(event));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.ingestUrl() + "/v1/events"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "ApiKey " + networkKey)
                .POST(HttpRequest.BodyPublishers.ofString("{\"events\":[" + String.join(",", events) + "]}"))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            if ((error != null || response == null || response.statusCode() >= 500) && attempt < 20) {
                scheduler.schedule(() -> reconnect(e, attempt + 1), 200, TimeUnit.MILLISECONDS);
            } else if (response != null && response.body().contains("\"REJECTED\"")) {
                log.warn("event-ingest rejected the reconnect of {}: {}", e.iccid, response.body());
            }
        });
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
