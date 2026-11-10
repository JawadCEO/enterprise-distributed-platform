package com.edp.iot.gateway.ingest;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
import com.edp.iot.common.event.TelemetryEvent;
import com.edp.iot.gateway.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Writes events to {@code telemetry.raw}, keyed by device ID, and reports which writes Kafka
 * confirmed. With {@code acks=all} and {@code min.insync.replicas=2} a confirmed write is on at
 * least two brokers (FR-2).
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final GatewayProperties props;

    public EventPublisher(KafkaTemplate<String, String> kafka, GatewayProperties props) {
        this.kafka = kafka;
        this.props = props;
    }

    /** @return one flag per event, true when Kafka confirmed the write in time */
    public boolean[] publish(List<TelemetryEvent> events) {
        List<CompletableFuture<?>> futures = new ArrayList<>(events.size());
        for (TelemetryEvent event : events) {
            CompletableFuture<?> future;
            try {
                future = kafka.send(Topics.RAW, event.deviceId(), Json.write(event));
            } catch (RuntimeException e) {
                future = CompletableFuture.failedFuture(e);
            }
            futures.add(future);
        }

        long deadline = System.nanoTime() + props.publishTimeout().toNanos();
        boolean[] confirmed = new boolean[events.size()];
        for (int i = 0; i < futures.size(); i++) {
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                futures.get(i).get(remaining, TimeUnit.NANOSECONDS);
                confirmed[i] = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("Kafka did not confirm event {}: {}", events.get(i).dedupId(), e.toString());
            }
        }
        return confirmed;
    }
}
