package com.edp.iot.gateway.ingest;

import com.edp.iot.common.event.TelemetryEvent;
import com.edp.iot.common.registry.DeviceInfo;
import com.edp.iot.gateway.GatewayProperties;
import com.edp.iot.gateway.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * One request: validate each event, take rate-limit allowance for the valid ones, publish the
 * allowed ones, and report a result per event. The overall HTTP status tells a simple device what
 * to do: 200 when anything was accepted or rejected for good, 429 when it was only rate limited,
 * and 503 when Kafka could not take the events or the gateway is shedding load (NFR-24).
 */
@Service
public class IngestService {

    public record Response(HttpStatus status, List<EventResult> results, Long retryAfterMs) {
    }

    private static final long SHED_RETRY_MS = 1000;

    private final EventValidator validator;
    private final RateLimiter rateLimiter;
    private final EventPublisher publisher;
    private final Semaphore inFlight;
    private final MeterRegistry meters;

    public IngestService(EventValidator validator, RateLimiter rateLimiter, EventPublisher publisher, GatewayProperties props,
                         MeterRegistry meters) {
        this.meters = meters;
        this.validator = validator;
        this.rateLimiter = rateLimiter;
        this.publisher = publisher;
        this.inFlight = new Semaphore(props.maxInFlightEvents());
    }

    public Response ingest(DeviceInfo device, List<JsonNode> nodes) {
        Instant receivedAt = Instant.now();
        EventResult[] results = new EventResult[nodes.size()];
        List<Integer> validIndexes = new ArrayList<>();
        List<TelemetryEvent> valid = new ArrayList<>();

        for (int i = 0; i < nodes.size(); i++) {
            EventValidator.Outcome outcome = validator.validate(nodes.get(i), device, receivedAt);
            if (outcome instanceof EventValidator.Valid v) {
                validIndexes.add(i);
                valid.add(v.event());
            } else if (outcome instanceof EventValidator.Invalid bad) {
                results[i] = EventResult.rejected(bad.sequence(), bad.code());
            }
        }

        int allowed = valid.isEmpty() ? 0 : rateLimiter.acquire(device, valid.size());
        long retryAfter = rateLimiter.millisUntilNextWindow();
        for (int j = allowed; j < valid.size(); j++) {
            results[validIndexes.get(j)] = EventResult.rateLimited(valid.get(j).sequence(), retryAfter);
        }

        List<TelemetryEvent> toPublish = valid.subList(0, allowed);
        int accepted = 0;
        boolean shed = false;
        if (!toPublish.isEmpty()) {
            if (!inFlight.tryAcquire(toPublish.size())) {
                shed = true;
                for (int j = 0; j < allowed; j++) {
                    results[validIndexes.get(j)] = EventResult.retry(toPublish.get(j).sequence(), SHED_RETRY_MS);
                }
            } else {
                try {
                    boolean[] confirmed = publisher.publish(toPublish);
                    for (int j = 0; j < allowed; j++) {
                        long seq = toPublish.get(j).sequence();
                        if (confirmed[j]) {
                            accepted++;
                            results[validIndexes.get(j)] = EventResult.accepted(seq);
                        } else {
                            results[validIndexes.get(j)] = EventResult.retry(seq, SHED_RETRY_MS);
                        }
                    }
                } finally {
                    inFlight.release(toPublish.size());
                }
            }
        }

        for (EventResult r : results) {
            meters.counter("iot.gateway.events", "result", r.status().toLowerCase()).increment();
        }
        boolean anyRetry = shed || accepted < allowed;
        if (accepted > 0) {
            return new Response(HttpStatus.OK, List.of(results), null);
        }
        if (anyRetry) {
            return new Response(HttpStatus.SERVICE_UNAVAILABLE, List.of(results), SHED_RETRY_MS);
        }
        if (allowed < valid.size()) {
            return new Response(HttpStatus.TOO_MANY_REQUESTS, List.of(results), retryAfter);
        }
        return new Response(HttpStatus.OK, List.of(results), null);
    }
}
