package com.edp.iot.processor.pipeline;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
import com.edp.iot.common.deadletter.DeadLetterEnvelope;
import com.edp.iot.common.deadletter.ReasonCodes;
import com.edp.iot.processor.ProcessorProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends failed events to the next retry tier or to the dead-letter topic. Callers collect the
 * returned futures and {@link #await(List) await} them before their offsets are committed, so a
 * failed event is never dropped between topics.
 */
@Component
public class FailureRouter {

    /** Where a record stands in its retry history. */
    public record Attempt(int failures, Instant firstFailureAt) {
        public static Attempt first() {
            return new Attempt(0, null);
        }
    }

    private final KafkaTemplate<String, String> kafka;
    private final ProcessorProperties props;

    public FailureRouter(KafkaTemplate<String, String> kafka, ProcessorProperties props) {
        this.kafka = kafka;
        this.props = props;
    }

    /** Routes a transient failure: the next tier while tiers remain, otherwise the dead-letter topic. */
    public CompletableFuture<?> retryOrDeadLetter(String sourceTopic, String key, String value, Attempt attempt, String error) {
        Instant now = Instant.now();
        Instant first = attempt.firstFailureAt() == null ? now : attempt.firstFailureAt();
        int failures = attempt.failures() + 1;
        if (failures > Topics.RETRY_TIERS.size()) {
            return deadLetter(sourceTopic, key, null, value, ReasonCodes.RETRIES_EXHAUSTED, error, failures, first);
        }
        Topics.RetryTier tier = Topics.RETRY_TIERS.get(failures - 1);
        ProducerRecord<String, String> record = new ProducerRecord<>(tier.topic(), key, value);
        Headers headers = record.headers();
        headers.add(Topics.HEADER_ATTEMPT, bytes(String.valueOf(failures)));
        headers.add(Topics.HEADER_RETRY_AT, bytes(String.valueOf(now.plus(tier.delay()).toEpochMilli())));
        headers.add(Topics.HEADER_FIRST_FAILURE_AT, bytes(String.valueOf(first.toEpochMilli())));
        headers.add(Topics.HEADER_LAST_ERROR, bytes(truncate(error)));
        return kafka.send(record);
    }

    public CompletableFuture<?> deadLetter(String sourceTopic, String deviceId, String tenantId, String value,
                                           String reasonCode, String reason, int attempts, Instant firstFailureAt) {
        Instant now = Instant.now();
        DeadLetterEnvelope envelope = new DeadLetterEnvelope(UUID.randomUUID(), sourceTopic, deviceId, tenantId, reasonCode,
                truncate(reason), attempts, firstFailureAt == null ? now : firstFailureAt, now, value);
        return kafka.send(Topics.DLQ, deviceId, Json.write(envelope));
    }

    /** Waits for the given sends. Throws if any failed, so the source records are redelivered. */
    public void await(List<CompletableFuture<?>> sends) {
        if (sends.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                    .get(props.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while routing failed events", e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not route failed events to retry or dead-letter topics", e);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
