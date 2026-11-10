package com.edp.iot.state.pipeline;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
import com.edp.iot.common.deadletter.ReasonCodes;
import com.edp.iot.common.event.NetworkEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Consumes {@code network.events} in batches. The happy path is one transaction per batch. If the
 * batch fails for any reason other than the database being down, the events are processed one at
 * a time so that only the failing ones go to the retry tiers or the dead-letter topic.
 */
@Component
public class EventListener {

    private static final Logger log = LoggerFactory.getLogger(EventListener.class);

    private final EventProcessor processor;
    private final FailureRouter router;
    private final FailureClassifier classifier;
    private final StateMetrics metrics;
    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public EventListener(EventProcessor processor, FailureRouter router, FailureClassifier classifier,
                         StateMetrics metrics, ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.processor = processor;
        this.router = router;
        this.classifier = classifier;
        this.metrics = metrics;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @KafkaListener(topics = Topics.NETWORK_EVENTS, groupId = "state-processor", containerFactory = "eventBatchFactory",
            concurrency = "${iot.state.concurrency:3}")
    public void onBatch(List<ConsumerRecord<String, String>> records) {
        metrics.batch(records.size());
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (t == null || p == null || records.isEmpty()) {
            handle(records);
            return;
        }
        // The batch span continues the trace of its first event, so a trace runs from event-ingest
        // across the Kafka hop into processing.
        Span span = p.extract(records.get(0).headers(), (headers, key) -> {
                    Header h = headers.lastHeader(key);
                    return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
                })
                .name("network.events process")
                .kind(Span.Kind.CONSUMER)
                .tag("messaging.batch.message_count", String.valueOf(records.size()))
                .start();
        try (Tracer.SpanInScope ignored = t.withSpan(span)) {
            handle(records);
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private void handle(List<ConsumerRecord<String, String>> records) {
        List<CompletableFuture<?>> sends = new ArrayList<>();
        List<ConsumerRecord<String, String>> parsedRecords = new ArrayList<>(records.size());
        List<NetworkEvent> events = new ArrayList<>(records.size());

        for (ConsumerRecord<String, String> record : records) {
            try {
                NetworkEvent event = Json.read(record.value(), NetworkEvent.class);
                if (event.eventId() == null || event.type() == null || event.lineId() == null || event.receivedAt() == null) {
                    throw new IllegalArgumentException("Missing eventId, type, lineId or receivedAt");
                }
                events.add(event);
                parsedRecords.add(record);
            } catch (RuntimeException e) {
                metrics.deadLettered(1);
                sends.add(router.deadLetter(record.topic(), record.key(), null, record.value(),
                        ReasonCodes.MALFORMED_RECORD, e.getMessage(), 1, null));
            }
        }

        if (!events.isEmpty()) {
            try {
                deadLetters(processor.process(events), sends);
            } catch (RuntimeException e) {
                if (classifier.classify(e) == FailureClassifier.Kind.DEPENDENCY_DOWN) {
                    throw e;
                }
                log.warn("Batch of {} failed ({}); processing events one by one", events.size(), e.toString());
                for (int i = 0; i < events.size(); i++) {
                    processOne(parsedRecords.get(i), events.get(i), sends);
                }
            }
        }
        router.await(sends);
    }

    private void processOne(ConsumerRecord<String, String> record, NetworkEvent event, List<CompletableFuture<?>> sends) {
        try {
            deadLetters(processor.process(List.of(event)), sends);
        } catch (RuntimeException e) {
            switch (classifier.classify(e)) {
                case DEPENDENCY_DOWN -> throw e;
                case TRANSIENT -> {
                    metrics.retried();
                    sends.add(router.retryOrDeadLetter(record.topic(), record.key(), record.value(),
                            FailureRouter.Attempt.first(), e.toString()));
                }
                case PERMANENT -> {
                    metrics.deadLettered(1);
                    sends.add(router.deadLetter(record.topic(), event.lineId(), event.customerId(),
                            record.value(), ReasonCodes.PROCESSING_ERROR, e.toString(), 1, null));
                }
            }
        }
    }

    private void deadLetters(EventProcessor.Result result, List<CompletableFuture<?>> sends) {
        for (EventProcessor.DeadLetter dl : result.deadLetters()) {
            sends.add(router.deadLetter(Topics.NETWORK_EVENTS, dl.event().lineId(), dl.event().customerId(),
                    Json.write(dl.event()), dl.reasonCode(), dl.reason(), 1, null));
        }
    }
}
