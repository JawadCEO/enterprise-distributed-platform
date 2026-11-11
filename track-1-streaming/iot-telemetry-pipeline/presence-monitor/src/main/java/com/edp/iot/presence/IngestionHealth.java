package com.edp.iot.presence;

import com.edp.iot.common.Topics;
import com.edp.iot.common.presence.PresenceKeys;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Answers "is the platform currently receiving and processing events?". Both halves must hold
 * before a silent line can be blamed on its device rather than on the platform.
 */
@Component
class IngestionHealth {

    private static final Logger log = LoggerFactory.getLogger(IngestionHealth.class);

    private final StringRedisTemplate redis;
    private final AdminClient admin;
    private final PresenceProperties props;

    IngestionHealth(StringRedisTemplate redis, AdminClient admin, PresenceProperties props) {
        this.redis = redis;
        this.admin = admin;
        this.props = props;
    }

    record Snapshot(boolean ingestUp, long processorLag) {
        boolean healthy(long maxLag) {
            return ingestUp && processorLag >= 0 && processorLag <= maxLag;
        }
    }

    Snapshot check() {
        return new Snapshot(ingestUp(), processorLag());
    }

    private boolean ingestUp() {
        long since = System.currentTimeMillis() - props.beaconMaxAge().toMillis();
        Long count = redis.opsForZSet().count(PresenceKeys.INGEST_BEACONS, since, Double.POSITIVE_INFINITY);
        redis.opsForZSet().removeRangeByScore(PresenceKeys.INGEST_BEACONS, 0, since - 3_600_000);
        return count != null && count > 0;
    }

    /** Records on {@code network.events} not yet committed by state-processor, or -1 if Kafka cannot tell. */
    private long processorLag() {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(props.processorGroup())
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latestSpec = new HashMap<>();
            admin.describeTopics(java.util.List.of(Topics.NETWORK_EVENTS)).allTopicNames().get(5, TimeUnit.SECONDS)
                    .get(Topics.NETWORK_EVENTS).partitions()
                    .forEach(p -> latestSpec.put(new TopicPartition(Topics.NETWORK_EVENTS, p.partition()), OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest =
                    admin.listOffsets(latestSpec).all().get(5, TimeUnit.SECONDS);
            long lag = 0;
            for (var entry : latest.entrySet()) {
                OffsetAndMetadata done = committed.get(entry.getKey());
                lag += entry.getValue().offset() - (done == null ? 0 : done.offset());
            }
            return lag;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Could not read processor lag: {}", e.toString());
            return -1;
        }
    }
}
