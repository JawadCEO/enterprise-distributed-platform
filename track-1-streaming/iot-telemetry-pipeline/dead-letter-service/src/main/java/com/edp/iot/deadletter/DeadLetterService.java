package com.edp.iot.deadletter;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
import com.edp.iot.common.deadletter.DeadLetterEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Stores dead letters from {@code telemetry.dlq} and replays them onto {@code telemetry.raw}.
 * Replay is safe because processing is idempotent: a replayed event that was already applied is
 * a duplicate, and one older than the telemetry retention updates status only (FR-12).
 */
@Service
class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);
    private static final Duration RETENTION = Duration.ofDays(30);

    private final DeadLetterRepository repository;
    private final KafkaTemplate<String, String> kafka;

    DeadLetterService(DeadLetterRepository repository, KafkaTemplate<String, String> kafka) {
        this.repository = repository;
        this.kafka = kafka;
    }

    @KafkaListener(topics = Topics.DLQ, groupId = "dead-letter-service")
    void onDeadLetter(ConsumerRecord<String, String> record) {
        DeadLetterEnvelope envelope;
        try {
            envelope = Json.read(record.value(), DeadLetterEnvelope.class);
        } catch (RuntimeException e) {
            log.error("Unreadable dead-letter record at {}-{}@{}, skipped: {}",
                    record.topic(), record.partition(), record.offset(), e.toString());
            return;
        }
        repository.insert(envelope);
    }

    boolean replay(DeadLetterRepository.Replayable item) {
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.RAW, item.deviceId(), item.payload());
        record.headers().add(Topics.HEADER_REPLAY, item.deadLetterId().toString().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Replay of {} failed: {}", item.deadLetterId(), e.toString());
            return false;
        }
        repository.markReplayed(item.deadLetterId());
        return true;
    }

    int replayAll(List<DeadLetterRepository.Replayable> items) {
        int replayed = 0;
        for (DeadLetterRepository.Replayable item : items) {
            if (replay(item)) {
                replayed++;
            }
        }
        return replayed;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    void expire() {
        int deleted;
        do {
            deleted = repository.deleteOlderThan(Instant.now().minus(RETENTION), 5_000);
        } while (deleted == 5_000);
    }
}
