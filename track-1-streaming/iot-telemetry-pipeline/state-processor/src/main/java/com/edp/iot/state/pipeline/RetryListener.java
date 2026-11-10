package com.edp.iot.state.pipeline;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
import com.edp.iot.common.deadletter.ReasonCodes;
import com.edp.iot.common.event.NetworkEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Consumes the retry tiers. Every record in one tier waits the same delay, so records come due in
 * the order they were written: when the head record is not due yet, the partition is paused with
 * {@code nack} until it is, and nothing behind it is due either.
 */
@Component
public class RetryListener {

    /** Longest single pause; a longer wait is split into several so the consumer stays responsive. */
    private static final Duration MAX_PAUSE = Duration.ofSeconds(30);

    private final EventProcessor processor;
    private final FailureRouter router;
    private final FailureClassifier classifier;

    public RetryListener(EventProcessor processor, FailureRouter router, FailureClassifier classifier) {
        this.processor = processor;
        this.router = router;
        this.classifier = classifier;
    }

    @KafkaListener(topics = {Topics.RETRY_5S, Topics.RETRY_1M, Topics.RETRY_10M}, groupId = "state-processor-retry",
            containerFactory = "retryFactory")
    public void onRecord(ConsumerRecord<String, String> record, Acknowledgment ack) {
        long retryAt = longHeader(record, Topics.HEADER_RETRY_AT, 0);
        long waitMs = retryAt - System.currentTimeMillis();
        if (waitMs > 0) {
            ack.nack(Duration.ofMillis(Math.min(waitMs, MAX_PAUSE.toMillis())));
            return;
        }

        int failures = (int) longHeader(record, Topics.HEADER_ATTEMPT, 1);
        long first = longHeader(record, Topics.HEADER_FIRST_FAILURE_AT, System.currentTimeMillis());
        FailureRouter.Attempt attempt = new FailureRouter.Attempt(failures, Instant.ofEpochMilli(first));
        List<CompletableFuture<?>> sends = new ArrayList<>();

        NetworkEvent event;
        try {
            event = Json.read(record.value(), NetworkEvent.class);
        } catch (RuntimeException e) {
            sends.add(router.deadLetter(record.topic(), record.key(), null, record.value(),
                    ReasonCodes.MALFORMED_RECORD, e.getMessage(), failures, attempt.firstFailureAt()));
            router.await(sends);
            ack.acknowledge();
            return;
        }

        try {
            for (EventProcessor.DeadLetter dl : processor.process(List.of(event)).deadLetters()) {
                sends.add(router.deadLetter(record.topic(), event.lineId(), event.customerId(), record.value(),
                        dl.reasonCode(), dl.reason(), failures, attempt.firstFailureAt()));
            }
        } catch (RuntimeException e) {
            switch (classifier.classify(e)) {
                case DEPENDENCY_DOWN -> throw e;
                case TRANSIENT -> sends.add(router.retryOrDeadLetter(Topics.NETWORK_EVENTS, record.key(), record.value(), attempt, e.toString()));
                case PERMANENT -> sends.add(router.deadLetter(record.topic(), event.lineId(), event.customerId(),
                        record.value(), ReasonCodes.PROCESSING_ERROR, e.toString(), failures + 1, attempt.firstFailureAt()));
            }
        }
        router.await(sends);
        ack.acknowledge();
    }

    private static long longHeader(ConsumerRecord<?, ?> record, String name, long fallback) {
        Header header = record.headers().lastHeader(name);
        if (header == null) {
            return fallback;
        }
        try {
            return Long.parseLong(new String(header.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
