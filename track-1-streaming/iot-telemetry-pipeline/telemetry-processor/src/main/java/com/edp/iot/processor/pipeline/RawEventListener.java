package com.edp.iot.processor.pipeline;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
import com.edp.iot.common.deadletter.ReasonCodes;
import com.edp.iot.common.event.TelemetryEvent;
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
 * Consumes {@code telemetry.raw} in batches. The happy path is one transaction per batch. If the
 * batch fails for any reason other than the database being down, the events are processed one at
 * a time so that only the failing ones go to the retry tiers or the dead-letter topic.
 */
@Component
public class RawEventListener {

    private static final Logger log = LoggerFactory.getLogger(RawEventListener.class);

    private final EventProcessor processor;
    private final FailureRouter router;
    private final FailureClassifier classifier;
    private final ProcessorMetrics metrics;
    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public RawEventListener(EventProcessor processor, FailureRouter router, FailureClassifier classifier,
                            ProcessorMetrics metrics, ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.processor = processor;
        this.router = router;
        this.classifier = classifier;
        this.metrics = metrics;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @KafkaListener(topics = Topics.RAW, groupId = "telemetry-processor", containerFactory = "rawBatchFactory",
            concurrency = "${iot.processor.concurrency:3}")
    public void onBatch(List<ConsumerRecord<String, String>> records) {
        metrics.batch(records.size());
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (t == null || p == null || records.isEmpty()) {
            handle(records);
            return;
        }
        // The batch span continues the trace of its first event, so a trace runs from the gateway
        // request across the Kafka hop into processing (Phase 5).
        Span span = p.extract(records.get(0).headers(), (headers, key) -> {
                    Header h = headers.lastHeader(key);
                    return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
                })
                .name("telemetry.raw process")
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
        List<TelemetryEvent> events = new ArrayList<>(records.size());

        for (ConsumerRecord<String, String> record : records) {
            try {
                events.add(Json.read(record.value(), TelemetryEvent.class));
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

    private void processOne(ConsumerRecord<String, String> record, TelemetryEvent event, List<CompletableFuture<?>> sends) {
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
                    sends.add(router.deadLetter(record.topic(), event.deviceId(), event.tenantId(),
                            record.value(), ReasonCodes.PROCESSING_ERROR, e.toString(), 1, null));
                }
            }
        }
    }

    private void deadLetters(EventProcessor.Result result, List<CompletableFuture<?>> sends) {
        for (EventProcessor.DeadLetter dl : result.deadLetters()) {
            sends.add(router.deadLetter(Topics.RAW, dl.event().deviceId(), dl.event().tenantId(), Json.write(dl.event()),
                    dl.reasonCode(), dl.reason(), 1, null));
        }
    }
}
