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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Drives the fleet: one scheduler thread generates events when each device is due, and sends run
 * asynchronously, at most one in flight per device so its events reach the gateway in order
 * (except where out-of-order delivery is injected on purpose).
 */
final class TrafficEngine {

    private static final Logger log = LoggerFactory.getLogger(TrafficEngine.class);

    private final SimulatorProperties props;
    private final Scenario scenario;
    private final HttpClient http;
    private final Stats stats = new Stats();
    private final Random random;
    private final List<SimDevice> devices = new ArrayList<>();
    private final PriorityQueue<SimDevice> due = new PriorityQueue<>((a, b) -> Long.compare(a.nextDueAt, b.nextDueAt));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Semaphore concurrency;
    private final ConcurrentLinkedQueue<String> ledger = new ConcurrentLinkedQueue<>();
    private volatile boolean generating = true;

    TrafficEngine(SimulatorProperties props, HttpClient http, Registrar registrar) {
        this.props = props;
        this.scenario = Scenario.of(props);
        this.http = http;
        this.random = new Random(props.seed());
        this.concurrency = new Semaphore(props.maxConcurrentRequests());
        int heartbeatMillis = (int) (props.heartbeatSeconds() * 1000 * scenario.heartbeatFactor());
        long now = System.currentTimeMillis();
        for (int i = 0; i < props.devices(); i++) {
            String id = Registrar.deviceId(i);
            String tenant = Registrar.tenantId(i % props.tenants());
            boolean noisy = i % props.tenants() == 0 && scenario.noisyTenantMultiplier() > 1;
            SimDevice device = new SimDevice(id, tenant, registrar.secret(id), heartbeatMillis, noisy, random);
            device.nextDueAt = now + random.nextInt(Math.max(1, heartbeatMillis));
            devices.add(device);
            due.add(device);
        }
    }

    Stats run() throws Exception {
        log.info("Scenario {}: {} devices, {} tenants, heartbeat {}s, duration {}", scenario.name(), props.devices(),
                props.tenants(), props.heartbeatSeconds() * scenario.heartbeatFactor(), props.duration());
        long start = System.currentTimeMillis();
        long end = start + props.duration().toMillis();
        scheduler.scheduleAtFixedRate(() -> log.info(stats.line((System.currentTimeMillis() - start) / 1000.0)),
                5, 5, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::flushLedger, 1, 1, TimeUnit.SECONDS);

        while (System.currentTimeMillis() < end) {
            long now = System.currentTimeMillis();
            while (!due.isEmpty() && due.peek().nextDueAt <= now) {
                SimDevice device = due.poll();
                tick(device, now);
                due.add(device);
            }
            if (random.nextDouble() < props.unknownDeviceRate() * scenario.faultFactor()) {
                sendUnknownDevice();
            }
            Thread.sleep(5);
        }

        generating = false;
        long drainUntil = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < drainUntil && devices.stream().anyMatch(this::pending)) {
            Thread.sleep(200);
        }
        scheduler.shutdownNow();
        flushLedger();
        log.info("Finished. {}", stats.line((System.currentTimeMillis() - start) / 1000.0));
        long unacked = devices.stream().mapToLong(d -> {
            synchronized (d) {
                return d.buffer.size();
            }
        }).sum();
        log.info("Events still unacknowledged at exit: {}", unacked);
        return stats;
    }

    private boolean pending(SimDevice d) {
        synchronized (d) {
            return !d.buffer.isEmpty();
        }
    }

    private void tick(SimDevice device, long now) {
        synchronized (device) {
            device.nextDueAt = now + device.heartbeatMillis + random.nextInt(Math.max(1, device.heartbeatMillis / 10));
            if (now < device.silentUntil) {
                return;
            }
            if (random.nextDouble() < scenario.silenceRate()) {
                // stays quiet for four heartbeat windows, long enough to be marked offline
                device.silentUntil = now + 4L * device.heartbeatMillis;
                stats.silences.increment();
                return;
            }
            if (random.nextDouble() < scenario.rebootRate()) {
                device.reboot();
                stats.reboots.increment();
            }
            int count = device.noisy ? scenario.noisyTenantMultiplier() : 1;
            for (int i = 0; i < count; i++) {
                device.buffer.addLast(device.nextEvent(random, now, props.clockSkewRate() * scenario.faultFactor(), scenario.faultFactor()));
                stats.generated.increment();
            }
        }
        trySend(device);
    }

    private void trySend(SimDevice device) {
        List<SimDevice.Event> batch;
        List<String> payload = new ArrayList<>();
        synchronized (device) {
            long now = System.currentTimeMillis();
            if (device.inFlight || device.buffer.isEmpty()) {
                return;
            }
            if (now < device.backoffUntil) {
                scheduleSend(device, device.backoffUntil - now);
                return;
            }
            batch = new ArrayList<>(device.buffer).subList(0, Math.min(props.maxBatch(), device.buffer.size()));
            batch = new ArrayList<>(batch);
            device.inFlight = true;
        }
        List<SimDevice.Event> order = new ArrayList<>(batch);
        double faults = scenario.faultFactor();
        if (order.size() > 1 && random.nextDouble() < props.outOfOrderRate() * faults) {
            Collections.reverse(order);
        }
        for (SimDevice.Event e : order) {
            payload.add(e.json());
        }
        int extra = 0;
        if (device.lastAcked != null && random.nextDouble() < props.duplicateRate() * faults) {
            payload.add(device.lastAcked.json());
            stats.duplicatesSent.increment();
            extra++;
        }
        if (random.nextDouble() < props.malformedRate() * faults) {
            payload.add("{\"deviceId\":\"" + device.deviceId + "\",\"type\":\"TELEMETRY\",\"metrics\":{\"batteryPct\":\"low\"}}");
            stats.malformedSent.increment();
            extra++;
        }

        String body = "{\"events\":[" + String.join(",", payload) + "]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.gatewayUrl() + "/v1/events"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Device-Id", device.deviceId)
                .header("Authorization", "Bearer " + device.secret)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        concurrency.acquireUninterruptibly();
        long sentAt = System.nanoTime();
        List<SimDevice.Event> sentOrder = order;
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    concurrency.release();
                    stats.recordLatency((System.nanoTime() - sentAt) / 1000);
                    onResponse(device, sentOrder, response, error);
                });
    }

    private void onResponse(SimDevice device, List<SimDevice.Event> sent, HttpResponse<String> response, Throwable error) {
        long retryAfterMs = 0;
        boolean failed = false;
        synchronized (device) {
            device.inFlight = false;
            if (error != null || response == null) {
                stats.requestErrors.increment();
                failed = true;
            } else if (response.statusCode() == 401 || response.statusCode() == 403) {
                stats.requestErrors.increment();
                failed = true;
            } else {
                JsonNode results = parseResults(response.body());
                if (results == null) {
                    stats.requestErrors.increment();
                    failed = true;
                } else {
                    for (int i = 0; i < sent.size() && i < results.size(); i++) {
                        JsonNode result = results.get(i);
                        SimDevice.Event event = sent.get(i);
                        switch (result.path("status").asText()) {
                            case "ACCEPTED" -> {
                                device.buffer.remove(event);
                                device.lastAcked = event;
                                stats.accepted.increment();
                                ledger.add(device.deviceId + "," + event.bootId() + "," + event.sequence() + "," + System.currentTimeMillis());
                            }
                            case "REJECTED" -> {
                                device.buffer.remove(event);
                                stats.rejected.increment();
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
            }
            if (failed) {
                device.consecutiveFailures++;
                retryAfterMs = Math.min(30_000, 250L << Math.min(7, device.consecutiveFailures));
            } else {
                device.consecutiveFailures = 0;
            }
            device.backoffUntil = System.currentTimeMillis() + retryAfterMs;
        }
        if (pending(device) && (generating || retryAfterMs < 30_000)) {
            scheduleSend(device, retryAfterMs);
        }
    }

    private void scheduleSend(SimDevice device, long delayMs) {
        try {
            scheduler.schedule(() -> trySend(device), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
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

    /** A device the platform has never heard of; the gateway must answer 403 (FR-21). */
    private void sendUnknownDevice() {
        String id = "dev-unknown-" + random.nextInt(1000);
        String body = "{\"events\":[{\"eventId\":\"" + UUID.randomUUID() + "\",\"deviceId\":\"" + id
                + "\",\"bootId\":1,\"sequence\":0,\"deviceTime\":\"" + java.time.Instant.now() + "\",\"type\":\"HEARTBEAT\"}]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.gatewayUrl() + "/v1/events"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Device-Id", id)
                .header("Authorization", "Bearer not-a-real-secret")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        stats.unknownDeviceSent.increment();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    }

    private void flushLedger() {
        if (ledger.isEmpty()) {
            return;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(Path.of(props.ledgerFile()), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            String line;
            while ((line = ledger.poll()) != null) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            log.warn("Could not write ledger: {}", e.toString());
        }
    }
}
