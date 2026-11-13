package com.edp.iot.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * End-of-run check of webhooks (FR-21 to FR-25). It compares three things: what the platform says
 * it sent (customer-api's notification list), what the simulated customers' endpoints accepted,
 * and what the simulator knows happened (its requests, usage and outage).
 */
final class NotificationCheck {

    private static final Logger log = LoggerFactory.getLogger(NotificationCheck.class);

    record Report(int notifications, int delivered, int pending, int parked, int received, int duplicatesReceived,
                  int deliveredNotReceived, int receivedUnknown, int stuckPending, int deadDelivered, long badSignatures,
                  int outOfOrder, int requestsChecked, int requestsWrong, int thresholdsSent, int thresholdsWrong,
                  int outageNotices, int outageNoticesWrong, int alertsDuringOutage, long refused, double p50Ms, double p95Ms) {
        boolean clean() {
            return deliveredNotReceived == 0 && receivedUnknown == 0 && stuckPending == 0 && deadDelivered == 0
                    && badSignatures == 0 && outOfOrder == 0 && requestsWrong == 0 && thresholdsWrong == 0
                    && outageNoticesWrong == 0 && alertsDuringOutage == 0;
        }
    }

    private record Platform(String id, String customerId, String type, String lineId, String status, Instant createdAt,
                            JsonNode payload) {
    }

    private final SimulatorProperties props;
    private final HttpClient http;
    private final Registrar registrar;
    private final WebhookReceiver receiver;

    NotificationCheck(SimulatorProperties props, HttpClient http, Registrar registrar, WebhookReceiver receiver) {
        this.props = props;
        this.http = http;
        this.registrar = registrar;
        this.receiver = receiver;
    }

    Report run(Instant runStart, Instant trafficEnd, List<SimLine> lines, Map<String, Reconciler.Usage> usageBefore,
               List<RequestDriver.Tracked> requests, long[] outageWindow, Set<String> outageLines) throws Exception {
        List<String> customers = new ArrayList<>();
        for (int c = 0; c < props.customers(); c++) {
            customers.add(Registrar.customerId(c));
        }
        waitForDelivery(customers, runStart);

        List<Platform> platform = new ArrayList<>();
        for (String customerId : customers) {
            platform.addAll(list(customerId, runStart));
        }
        Map<String, Platform> byId = new HashMap<>();
        platform.forEach(p -> byId.put(p.id(), p));
        List<WebhookReceiver.Received> accepted = receiver.accepted();

        // delivery: every notification the platform calls delivered arrived, signed, and nothing else did
        Map<String, WebhookReceiver.Received> firstReceipt = new LinkedHashMap<>();
        int duplicates = 0;
        for (WebhookReceiver.Received r : accepted) {
            if (firstReceipt.putIfAbsent(r.notificationId(), r) != null) {
                duplicates++;
            }
        }
        int delivered = 0, pending = 0, parked = 0, deliveredNotReceived = 0, stuck = 0, deadDelivered = 0;
        for (Platform p : platform) {
            boolean dead = receiver.behaviour(p.customerId()).equals("dead");
            switch (p.status()) {
                case "DELIVERED" -> {
                    delivered++;
                    if (dead) {
                        deadDelivered++;
                    }
                    if (!firstReceipt.containsKey(p.id())) {
                        deliveredNotReceived++;
                        log.warn("Notification {} ({} for {}) is DELIVERED but never arrived", p.id(), p.type(), p.customerId());
                    }
                }
                case "PARKED" -> parked++;
                default -> {
                    pending++;
                    if (!dead) {
                        stuck++;
                        log.warn("Notification {} ({} for {}) is still PENDING", p.id(), p.type(), p.customerId());
                    }
                }
            }
        }
        int unknown = 0;
        for (WebhookReceiver.Received r : firstReceipt.values()) {
            if (!byId.containsKey(r.notificationId())) {
                unknown++;
                log.warn("Endpoint of {} accepted notification {} that the platform does not list", r.customerId(),
                        r.notificationId());
            }
        }

        // order per line: arrivals follow the order the platform created them in
        int outOfOrder = 0;
        Map<String, Instant> lastCreated = new HashMap<>();
        for (WebhookReceiver.Received r : firstReceipt.values()) {
            Platform p = byId.get(r.notificationId());
            if (p == null || r.lineId() == null) {
                continue;
            }
            String key = r.customerId() + "/" + r.lineId();
            Instant previous = lastCreated.get(key);
            if (previous != null && p.createdAt().isBefore(previous)) {
                outOfOrder++;
                log.warn("Notification {} for line {} arrived after a later one", p.id(), r.lineId());
            }
            lastCreated.merge(key, p.createdAt(), (a, b) -> a.isAfter(b) ? a : b);
        }

        // requests: one notification per finished request, of the matching type
        Map<String, List<Platform>> byRequest = new HashMap<>();
        for (Platform p : platform) {
            if (p.type().startsWith("REQUEST_")) {
                byRequest.computeIfAbsent(p.payload().path("requestId").asText(), k -> new ArrayList<>()).add(p);
            }
        }
        int requestsChecked = 0, requestsWrong = 0;
        for (RequestDriver.Tracked t : requests) {
            if (t.status.equals("PENDING")) {
                continue;
            }
            requestsChecked++;
            List<Platform> sent = byRequest.getOrDefault(t.requestId, List.of());
            String expected = t.status.equals("COMPLETED") ? "REQUEST_COMPLETED" : "REQUEST_FAILED";
            if (sent.size() != 1 || !sent.get(0).type().equals(expected)) {
                requestsWrong++;
                log.warn("Request {} ended {} but has {} notifications: {}", t.requestId, t.status, sent.size(),
                        sent.stream().map(Platform::type).toList());
            }
        }

        // usage thresholds: one each for 80% and 100%, only when the run crossed it (FR-25)
        Map<String, List<Platform>> thresholdsByLine = new HashMap<>();
        int thresholdsSent = 0;
        for (Platform p : platform) {
            if (p.type().startsWith("USAGE_")) {
                thresholdsSent++;
                thresholdsByLine.computeIfAbsent(p.lineId(), k -> new ArrayList<>()).add(p);
            }
        }
        int thresholdsWrong = 0;
        for (SimLine line : lines) {
            JsonNode body = get("/v1/lines/" + line.iccid, line.customerId);
            if (body == null) {
                continue;
            }
            long allowance = body.path("usage").path("allowanceBytes").asLong();
            long after = body.path("usage").path("bytesUsed").asLong();
            Reconciler.Usage u = usageBefore.get(line.iccid);
            long before = u == null || !u.cycleStart().equals(body.path("usage").path("cycleStart").asText()) ? 0 : u.bytesUsed();
            List<Platform> sent = thresholdsByLine.getOrDefault(line.iccid, List.of());
            for (int pct : new int[]{80, 100}) {
                boolean crossed = allowance > 0 && before * 100 < pct * allowance && after * 100 >= pct * allowance;
                long count = sent.stream().filter(p -> p.type().equals("USAGE_" + pct)).count();
                if (count != (crossed ? 1 : 0)) {
                    thresholdsWrong++;
                    log.warn("Line {}: usage {} -> {} of {} bytes, expected {} USAGE_{} notifications, platform has {}",
                            line.iccid, before, after, allowance, crossed ? 1 : 0, pct, count);
                }
            }
        }

        // outage: one start and one end notice per customer, no line alerts in the region meanwhile;
        // without an outage scenario, no outage notice at all. Once traffic stops every region goes
        // quiet, and the platform rightly calls that an outage if the checks take longer than its
        // detection window, so only notices from while traffic ran count.
        int outageNotices = 0, outageWrong = 0, alertsDuringOutage = 0, afterTraffic = 0;
        Map<String, int[]> noticesByCustomer = new HashMap<>();
        for (Platform p : platform) {
            if (p.type().startsWith("OUTAGE_") && p.createdAt().isAfter(trafficEnd)) {
                afterTraffic++;
            } else if (p.type().startsWith("OUTAGE_")) {
                outageNotices++;
                int[] counts = noticesByCustomer.computeIfAbsent(p.customerId(), k -> new int[2]);
                counts[p.type().equals("OUTAGE_STARTED") ? 0 : 1]++;
            }
        }
        if (afterTraffic > 0) {
            log.info("{} outage notices after traffic stopped, not counted", afterTraffic);
        }
        if (outageWindow == null) {
            outageWrong = outageNotices;
            if (outageNotices > 0) {
                log.warn("{} outage notices although no region went silent", outageNotices);
            }
        } else {
            for (String customerId : customers) {
                int[] counts = noticesByCustomer.getOrDefault(customerId, new int[2]);
                if (counts[0] != 1 || counts[1] != 1) {
                    outageWrong++;
                    log.warn("{} got {} OUTAGE_STARTED and {} OUTAGE_ENDED notices; expected one of each", customerId,
                            counts[0], counts[1]);
                }
            }
            // lines connected when the region went silent; a line first activated meanwhile is rightly
            // announced when it first comes online
            Set<String> inRegion = new HashSet<>(outageLines);
            Instant from = Instant.ofEpochMilli(outageWindow[0]);
            Instant to = Instant.ofEpochMilli(outageWindow[1]).plusSeconds(150);
            for (Platform p : platform) {
                if ((p.type().equals("DEVICE_OFFLINE") || p.type().equals("DEVICE_ONLINE")) && inRegion.contains(p.lineId())) {
                    Instant at = Instant.parse(p.payload().path("at").asText());
                    if (!at.isBefore(from) && at.isBefore(to)) {
                        alertsDuringOutage++;
                    }
                }
            }
            if (alertsDuringOutage > 0) {
                log.warn("{} line alerts in region {} during the outage", alertsDuringOutage, props.outageRegion());
            }
        }

        // latency to an endpoint that answers at once (NFR-2a)
        List<Long> latencies = new ArrayList<>();
        for (WebhookReceiver.Received r : firstReceipt.values()) {
            if (receiver.behaviour(r.customerId()).equals("ok") && r.body().hasNonNull("at")) {
                latencies.add(r.receivedAt() - Instant.parse(r.body().path("at").asText()).toEpochMilli());
            }
        }
        latencies.sort(Long::compare);
        double p50 = latencies.isEmpty() ? 0 : latencies.get(latencies.size() / 2);
        double p95 = latencies.isEmpty() ? 0 : latencies.get(Math.min(latencies.size() - 1, (int) (latencies.size() * 0.95)));

        Map<String, Integer> byType = new java.util.TreeMap<>();
        platform.forEach(p -> byType.merge(p.type(), 1, Integer::sum));
        log.info("Notifications by type: {}", byType);
        Map<String, String> perCustomer = new LinkedHashMap<>();
        for (String customerId : customers) {
            long d = platform.stream().filter(p -> p.customerId().equals(customerId) && p.status().equals("DELIVERED")).count();
            long k = platform.stream().filter(p -> p.customerId().equals(customerId) && p.status().equals("PARKED")).count();
            long w = platform.stream().filter(p -> p.customerId().equals(customerId) && p.status().equals("PENDING")).count();
            perCustomer.put(customerId + " (" + receiver.behaviour(customerId) + ")", d + " delivered, " + k + " parked, " + w + " pending");
        }
        log.info("Notifications by customer: {}", perCustomer);
        return new Report(platform.size(), delivered, pending, parked, firstReceipt.size(), duplicates, deliveredNotReceived,
                unknown, stuck, deadDelivered, receiver.badSignatures.sum(), outOfOrder, requestsChecked, requestsWrong,
                thresholdsSent, thresholdsWrong, outageNotices, outageWrong, alertsDuringOutage, receiver.refused.sum(), p50, p95);
    }

    /** Waits until no customer with a working endpoint has a notification left to deliver. */
    private void waitForDelivery(List<String> customers, Instant runStart) throws Exception {
        long deadline = System.currentTimeMillis() + props.notificationWait().toMillis();
        while (System.currentTimeMillis() < deadline) {
            int waiting = 0;
            for (String customerId : customers) {
                if (receiver.behaviour(customerId).equals("dead")) {
                    continue;
                }
                JsonNode page = get("/v1/notifications?status=PENDING&limit=1000", customerId);
                if (page != null) {
                    for (JsonNode n : page.path("items")) {
                        if (!Instant.parse(n.path("createdAt").asText()).isBefore(runStart)) {
                            waiting++;
                        }
                    }
                }
            }
            if (waiting == 0) {
                return;
            }
            log.info("Waiting for {} notifications to be delivered", waiting);
            Thread.sleep(3000);
        }
    }

    private List<Platform> list(String customerId, Instant since) throws Exception {
        List<Platform> out = new ArrayList<>();
        String cursor = null;
        while (true) {
            String path = "/v1/notifications?limit=1000" + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            JsonNode page = get(path, customerId);
            if (page == null) {
                throw new IllegalStateException("Could not list notifications for " + customerId);
            }
            boolean older = false;
            for (JsonNode n : page.path("items")) {
                Instant created = Instant.parse(n.path("createdAt").asText());
                if (created.isBefore(since)) {
                    older = true;
                    break;
                }
                out.add(new Platform(n.path("notificationId").asText(), customerId, n.path("type").asText(),
                        n.path("lineId").isMissingNode() || n.path("lineId").isNull() ? null : n.path("lineId").asText(),
                        n.path("status").asText(), created, n.path("payload")));
            }
            JsonNode next = page.path("nextCursor");
            if (older || next.isNull() || next.isMissingNode()) {
                return out;
            }
            cursor = next.asText();
        }
    }

    private JsonNode get(String path, String customerId) {
        try {
            HttpResponse<String> response = http.send(registrar.request(path, customerId).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Jsons.read(response.body()) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
