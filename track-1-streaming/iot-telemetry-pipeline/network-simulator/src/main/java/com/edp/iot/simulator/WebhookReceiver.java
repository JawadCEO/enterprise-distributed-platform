package com.edp.iot.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/**
 * The customers' webhook endpoints, served by the simulator. Each customer's endpoint behaves as
 * configured (ok, flaky, late, slow, dead), checks the signature like a customer should, and keeps
 * every notification it accepted, in arrival order, for the end-of-run check.
 */
final class WebhookReceiver {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiver.class);

    /** One accepted delivery. */
    record Received(String notificationId, String customerId, String type, String lineId, long receivedAt, JsonNode body) {
    }

    private final SimulatorProperties props;
    private final Map<String, String> behaviourByCustomer = new ConcurrentHashMap<>();
    private final Map<String, String> secrets = new ConcurrentHashMap<>();
    private final List<Received> accepted = new ArrayList<>();
    private final Random random = new Random(7);
    private final long startedAt = System.currentTimeMillis();
    final LongAdder refused = new LongAdder();
    final LongAdder badSignatures = new LongAdder();
    private HttpServer server;

    WebhookReceiver(SimulatorProperties props) {
        this.props = props;
        String[] behaviours = props.webhookBehaviours().split(",");
        for (int c = 0; c < props.customers(); c++) {
            behaviourByCustomer.put(Registrar.customerId(c), behaviours[c % behaviours.length].trim());
        }
    }

    String behaviour(String customerId) {
        return behaviourByCustomer.getOrDefault(customerId, "ok");
    }

    void secrets(Map<String, String> bySecret) {
        secrets.putAll(bySecret);
    }

    /** Starts the server and returns the base URL the platform should call. */
    String start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(props.webhookPort()), 0);
        server.setExecutor(Executors.newFixedThreadPool(32));
        server.createContext("/hooks/", this::handle);
        server.start();
        String host = props.webhookHost().isBlank() ? InetAddress.getLocalHost().getHostAddress() : props.webhookHost();
        return "http://" + host + ":" + props.webhookPort();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    synchronized List<Received> accepted() {
        return new ArrayList<>(accepted);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            String customerId = exchange.getRequestURI().getPath().substring("/hooks/".length());
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String behaviour = behaviour(customerId);
            boolean fail = switch (behaviour) {
                case "dead" -> true;
                case "late" -> System.currentTimeMillis() - startedAt < 60_000;
                case "flaky" -> {
                    synchronized (random) {
                        yield random.nextDouble() < 0.3;
                    }
                }
                default -> false;
            };
            if ("slow".equals(behaviour)) {
                Thread.sleep(2000);
            }
            if (fail) {
                refused.increment();
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            String secret = secrets.get(customerId);
            String timestamp = exchange.getRequestHeaders().getFirst("X-Webhook-Timestamp");
            String signature = exchange.getRequestHeaders().getFirst("X-Webhook-Signature");
            if (secret == null || timestamp == null || !valid(secret, timestamp, body, signature)
                    || Math.abs(System.currentTimeMillis() / 1000 - Long.parseLong(timestamp)) > 300) {
                badSignatures.increment();
                log.warn("Webhook for {} with a bad or missing signature", customerId);
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            JsonNode json = Jsons.read(body);
            Received r = new Received(json.path("notificationId").asText(), customerId, json.path("type").asText(),
                    json.path("lineId").isMissingNode() ? null : json.path("lineId").asText(), System.currentTimeMillis(), json);
            synchronized (this) {
                accepted.add(r);
            }
            exchange.sendResponseHeaders(204, -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Webhook handling failed: {}", e.toString());
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    /** What a customer does: recompute HMAC-SHA256 of "timestamp.body" with its secret and compare. */
    private static boolean valid(String secret, String timestamp, String body, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "v1=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
            return signature != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
