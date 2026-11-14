package com.edp.iot.ingest.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Optional MQTT path into event-ingest (Phase 0 PoC, ADR-003). Devices publish one event per message
 * to {@code iot/events/<iccid>} at QoS 1; every event-ingest instance takes a share of them through a
 * shared subscription and sends them through the same validation, rate limits and Kafka publish as
 * {@code POST /v1/events}. Off unless {@code enabled}.
 */
@ConfigurationProperties("iot.ingest.mqtt")
public record MqttProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("tcp://localhost:1883") String brokerUrl,
        @DefaultValue("$share/event-ingest/iot/events/+") String subscription,
        /* Events taken from the queue per call to the ingest path, like one HTTP batch. */
        @DefaultValue("100") int batch,
        @DefaultValue("2") int workers,
        /* Messages received but not yet acknowledged to the broker; more stay at the broker. */
        @DefaultValue("1000") int maxInflight,
        @DefaultValue("200ms") Duration retryDelay) {
}
