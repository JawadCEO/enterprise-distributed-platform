package com.edp.iot.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polling vs push test (Phase 0 PoC). While a traffic run goes on, this polls every line's current
 * state ({@code GET /v1/lines/{iccid}}) once per {@code pollSeconds}, spread evenly, the way a
 * customer integration polls today. Afterwards it takes each online/offline webhook the customers
 * received and finds when polling first showed the same change, so both are measured from the same
 * moment: the event that caused the change.
 */
final class PollProbe {

    private static final Logger log = LoggerFactory.getLogger(PollProbe.class);

    private record Seen(long at, String connection) {
    }

    private final SimulatorProperties props;
    private final HttpClient http;
    private final Registrar registrar;
    private final Map<String, List<Seen>> seen = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private ScheduledExecutorService timer;
    private long startedAt;

    PollProbe(SimulatorProperties props, HttpClient http, Registrar registrar) {
        this.props = props;
        this.http = http;
        this.registrar = registrar;
    }

    void start(List<SimLine> lines) {
        startedAt = System.currentTimeMillis();
        timer = Executors.newSingleThreadScheduledExecutor();
        long periodMicros = Math.max(1, props.pollSeconds() * 1_000_000L / lines.size());
        int[] next = {0};
        timer.scheduleAtFixedRate(() -> {
            SimLine line = lines.get(next[0]);
            next[0] = (next[0] + 1) % lines.size();
            requests.incrementAndGet();
            http.sendAsync(registrar.request("/v1/lines/" + line.iccid, line.customerId).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).whenComplete((r, e) -> {
                long now = System.currentTimeMillis();
                if (e != null || r.statusCode() != 200) {
                    errors.incrementAndGet();
                    return;
                }
                String connection = Jsons.read(r.body()).path("device").path("connection").asText("");
                seen.computeIfAbsent(line.iccid, k -> Collections.synchronizedList(new ArrayList<>())).add(new Seen(now, connection));
            });
        }, 0, periodMicros, TimeUnit.MICROSECONDS);
    }

    void stop() {
        timer.shutdownNow();
    }

    /** Compares polling with the online/offline webhooks received from customers whose endpoints answer at once. */
    void report(List<WebhookReceiver.Received> received, String okCustomers) {
        double seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
        Map<String, List<WebhookReceiver.Received>> byLine = new HashMap<>();
        for (WebhookReceiver.Received r : received) {
            if ((r.type().equals("DEVICE_ONLINE") || r.type().equals("DEVICE_OFFLINE")) && r.lineId() != null
                    && okCustomers.contains(r.customerId())) {
                byLine.computeIfAbsent(r.lineId(), k -> new ArrayList<>()).add(r);
            }
        }
        List<Long> push = new ArrayList<>();
        List<Long> poll = new ArrayList<>();
        int changes = 0;
        int missed = 0;
        for (Map.Entry<String, List<WebhookReceiver.Received>> entry : byLine.entrySet()) {
            List<WebhookReceiver.Received> hooks = entry.getValue();
            hooks.sort(Comparator.comparing(h -> h.body().path("at").asText()));
            List<Seen> polls = new ArrayList<>(seen.getOrDefault(entry.getKey(), List.of()));
            polls.sort(Comparator.comparingLong(Seen::at));
            for (int i = 0; i < hooks.size(); i++) {
                WebhookReceiver.Received h = hooks.get(i);
                long at = Instant.parse(h.body().path("at").asText()).toEpochMilli();
                long until = i + 1 < hooks.size() ? Instant.parse(hooks.get(i + 1).body().path("at").asText()).toEpochMilli() : Long.MAX_VALUE;
                if (at < startedAt + props.pollSeconds() * 1000L) {
                    continue; // polling has not been round every line yet
                }
                String target = h.type().equals("DEVICE_ONLINE") ? "ONLINE" : "OFFLINE";
                changes++;
                push.add(h.receivedAt() - at);
                Long found = null;
                for (Seen s : polls) {
                    if (s.at() >= at && s.at() < until && s.connection().equals(target)) {
                        found = s.at() - at;
                        break;
                    }
                }
                if (found == null) {
                    missed++; // changed back before the next poll, or the run ended first
                } else {
                    poll.add(found);
                }
            }
        }
        log.info("Polling every {} s: {} requests ({}/s for {} lines, {} errors). Online/offline changes: {}; push noticed all, "
                        + "polling noticed {} and missed {}. Time to notice: push p50 {} ms, p95 {} ms; polling p50 {} ms, p95 {} ms",
                props.pollSeconds(), requests.get(), Math.round(requests.get() / seconds), seen.size(), errors.get(), changes,
                poll.size(), missed, pct(push, 50), pct(push, 95), pct(poll, 50), pct(poll, 95));
    }

    private static long pct(List<Long> values, int p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
