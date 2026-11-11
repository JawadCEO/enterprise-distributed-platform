package com.edp.iot.ingest.events;

import com.edp.iot.common.event.NetworkEvent;
import com.edp.iot.common.line.LineInfo;
import com.edp.iot.common.line.LineRegistry;
import com.edp.iot.ingest.IngestProperties;
import com.edp.iot.ingest.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * One request (data.md section 3, stages 1 and 2): validate each event, reject events for unknown
 * or deactivated lines with a reason (FR-13), take rate-limit allowance per line and customer,
 * publish the allowed ones, and report a result per event. The overall HTTP status tells a simple
 * sender what to do: 200 when anything was accepted or rejected for good, 429 when it was only
 * rate limited, and 503 when Kafka could not take the events or event-ingest is shedding load
 * (NFR-24).
 */
@Service
public class IngestService {

    public record Response(HttpStatus status, List<EventResult> results, Long retryAfterMs) {
    }

    private static final long SHED_RETRY_MS = 1000;

    private final EventValidator validator;
    private final LineRegistry registry;
    private final RateLimiter rateLimiter;
    private final EventPublisher publisher;
    private final Semaphore inFlight;
    private final MeterRegistry meters;

    public IngestService(EventValidator validator, LineRegistry registry, RateLimiter rateLimiter, EventPublisher publisher,
                         IngestProperties props, MeterRegistry meters) {
        this.validator = validator;
        this.registry = registry;
        this.rateLimiter = rateLimiter;
        this.publisher = publisher;
        this.meters = meters;
        this.inFlight = new Semaphore(props.maxInFlightEvents());
    }

    public Response ingest(List<JsonNode> nodes) {
        Instant receivedAt = Instant.now();
        EventResult[] results = new EventResult[nodes.size()];

        List<Integer> validIndexes = new ArrayList<>();
        List<NetworkEvent> valid = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            EventValidator.Outcome outcome = validator.validate(nodes.get(i), receivedAt);
            if (outcome instanceof EventValidator.Valid v) {
                validIndexes.add(i);
                valid.add(v.event());
            } else if (outcome instanceof EventValidator.Invalid bad) {
                results[i] = EventResult.rejected(bad.eventId(), bad.code());
            }
        }

        // Known, non-deactivated lines only, with the customer taken from the registry (FR-13).
        Map<String, LineInfo> lines = valid.isEmpty() ? Map.of()
                : registry.findAll(valid.stream().map(NetworkEvent::lineId).distinct().toList());
        Map<String, List<Integer>> byLine = new LinkedHashMap<>();
        Map<Integer, NetworkEvent> enriched = new LinkedHashMap<>();
        for (int j = 0; j < valid.size(); j++) {
            NetworkEvent e = valid.get(j);
            int index = validIndexes.get(j);
            LineInfo line = lines.get(e.lineId());
            if (line == null) {
                results[index] = EventResult.rejected(e.eventId().toString(), "UNKNOWN_LINE");
            } else if (line.isDeactivated()) {
                results[index] = EventResult.rejected(e.eventId().toString(), "INACTIVE_LINE");
            } else {
                enriched.put(index, withCustomer(e, line.customerId()));
                byLine.computeIfAbsent(e.lineId(), k -> new ArrayList<>()).add(index);
            }
        }

        // Rate limits per line and per customer. Within a line, the earliest events in the batch pass first.
        long retryAfter = rateLimiter.millisUntilNextWindow();
        List<Integer> allowedIndexes = new ArrayList<>();
        int limited = 0;
        for (Map.Entry<String, List<Integer>> entry : byLine.entrySet()) {
            List<Integer> indexes = entry.getValue();
            int allowed = rateLimiter.acquire(lines.get(entry.getKey()), indexes.size());
            for (int k = 0; k < indexes.size(); k++) {
                if (k < allowed) {
                    allowedIndexes.add(indexes.get(k));
                } else {
                    results[indexes.get(k)] = EventResult.rateLimited(enriched.get(indexes.get(k)).eventId().toString(), retryAfter);
                    limited++;
                }
            }
        }
        allowedIndexes.sort(Integer::compare);

        List<NetworkEvent> toPublish = allowedIndexes.stream().map(enriched::get).toList();
        int accepted = 0;
        boolean shed = false;
        if (!toPublish.isEmpty()) {
            if (!inFlight.tryAcquire(toPublish.size())) {
                shed = true;
                for (int index : allowedIndexes) {
                    results[index] = EventResult.retry(enriched.get(index).eventId().toString(), SHED_RETRY_MS);
                }
            } else {
                try {
                    boolean[] confirmed = publisher.publish(toPublish);
                    for (int j = 0; j < toPublish.size(); j++) {
                        String id = toPublish.get(j).eventId().toString();
                        if (confirmed[j]) {
                            accepted++;
                            results[allowedIndexes.get(j)] = EventResult.accepted(id);
                        } else {
                            results[allowedIndexes.get(j)] = EventResult.retry(id, SHED_RETRY_MS);
                        }
                    }
                } finally {
                    inFlight.release(toPublish.size());
                }
            }
        }

        for (EventResult r : results) {
            meters.counter("iot.ingest.events", "result", r.status().toLowerCase()).increment();
        }
        boolean anyRetry = shed || accepted < toPublish.size();
        if (accepted > 0) {
            return new Response(HttpStatus.OK, List.of(results), null);
        }
        if (anyRetry) {
            return new Response(HttpStatus.SERVICE_UNAVAILABLE, List.of(results), SHED_RETRY_MS);
        }
        if (limited > 0) {
            return new Response(HttpStatus.TOO_MANY_REQUESTS, List.of(results), retryAfter);
        }
        return new Response(HttpStatus.OK, List.of(results), null);
    }

    private static NetworkEvent withCustomer(NetworkEvent e, String customerId) {
        return new NetworkEvent(e.eventId(), e.type(), e.lineId(), e.imei(), customerId, e.bootId(), e.sequence(),
                e.deviceTime(), e.networkTime(), e.receivedAt(), e.payload());
    }
}
