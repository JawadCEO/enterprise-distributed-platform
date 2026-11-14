package com.edp.iot.ingest.mqtt;

import com.edp.iot.common.Json;
import com.edp.iot.ingest.events.EventResult;
import com.edp.iot.ingest.events.IngestService;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Takes device events from an MQTT broker and runs them through {@link IngestService}, the same path
 * as {@code POST /v1/events}. A message is acknowledged to the broker only once its event is in Kafka
 * or rejected for good, so the broker keeps it until then and redelivers it if this instance stops.
 * Events the platform asks to retry (Kafka unavailable, rate limited) are tried again here, because an
 * MQTT device cannot be told to resend after the broker has acknowledged it.
 *
 * The topic carries the line: an event whose {@code lineId} differs from the topic is rejected, so a
 * device can only report for the line it publishes as. Device authentication belongs at the broker
 * (client certificates per SIM) and is not part of this test.
 */
@Component
@ConditionalOnProperty(name = "iot.ingest.mqtt.enabled", havingValue = "true")
public class MqttIngest implements MqttCallbackExtended, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MqttIngest.class);

    private record Received(int messageId, int qos, String topicLine, JsonNode event) {
    }

    private final MqttProperties props;
    private final IngestService ingest;
    private final MeterRegistry meters;
    private final BlockingQueue<Received> queue = new LinkedBlockingQueue<>();
    private ExecutorService workers;
    private MqttAsyncClient client;
    private volatile boolean running;

    public MqttIngest(MqttProperties props, IngestService ingest, MeterRegistry meters) {
        this.props = props;
        this.ingest = ingest;
        this.meters = meters;
    }

    @Override
    public void start() {
        try {
            // A fixed client id with a persistent session: messages for this instance wait at the broker while it restarts.
            String id = "event-ingest-" + InetAddress.getLocalHost().getHostName();
            client = new MqttAsyncClient(props.brokerUrl(), id, new MemoryPersistence());
            client.setCallback(this);
            client.setManualAcks(true);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(false);
            options.setAutomaticReconnect(true);
            options.setMaxInflight(props.maxInflight());
            options.setKeepAliveInterval(10);
            client.connect(options).waitForCompletion(10_000);
            running = true;
            workers = Executors.newFixedThreadPool(props.workers());
            for (int i = 0; i < props.workers(); i++) {
                workers.submit(this::drain);
            }
            log.info("MQTT ingest connected to {} as {}", props.brokerUrl(), id);
        } catch (Exception e) {
            throw new IllegalStateException("MQTT ingest could not connect to " + props.brokerUrl(), e);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        try {
            client.subscribe(props.subscription(), 1);
        } catch (MqttException e) {
            log.error("MQTT subscribe to {} failed", props.subscription(), e);
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String line = topic.substring(topic.lastIndexOf('/') + 1);
        JsonNode event;
        try {
            event = Json.MAPPER.readTree(message.getPayload());
        } catch (Exception e) {
            event = null;
        }
        queue.add(new Received(message.getId(), message.getQos(), line, event));
    }

    private void drain() {
        List<Received> batch = new ArrayList<>(props.batch());
        while (running) {
            try {
                Received first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, props.batch() - 1);
                process(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("MQTT batch failed; it will be redelivered", e);
            } finally {
                batch.clear();
            }
        }
    }

    private void process(List<Received> batch) throws Exception {
        List<Received> pending = new ArrayList<>();
        List<JsonNode> nodes = new ArrayList<>();
        for (Received r : batch) {
            if (r.event() == null || !r.event().isObject() || !r.topicLine().equals(r.event().path("lineId").asText())) {
                meters.counter("iot.ingest.mqtt.events", "result", "rejected").increment();
                ack(r);
            } else {
                pending.add(r);
                nodes.add(r.event());
            }
        }
        while (!pending.isEmpty() && running) {
            IngestService.Response response = ingest.ingest(nodes);
            List<Received> again = new ArrayList<>();
            List<JsonNode> againNodes = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                String status = response.results().get(i).status();
                if (status.equals(EventResult.ACCEPTED) || status.equals(EventResult.REJECTED)) {
                    meters.counter("iot.ingest.mqtt.events", "result", status.toLowerCase()).increment();
                    ack(pending.get(i));
                } else {
                    again.add(pending.get(i));
                    againNodes.add(nodes.get(i));
                }
            }
            if (!again.isEmpty()) {
                meters.counter("iot.ingest.mqtt.retries").increment(again.size());
                Thread.sleep(props.retryDelay().toMillis());
            }
            pending = again;
            nodes = againNodes;
        }
    }

    private void ack(Received r) throws MqttException {
        if (r.qos() > 0) {
            client.messageArrivedComplete(r.messageId(), r.qos());
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost; reconnecting", cause);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    @Override
    public void stop() {
        running = false;
        if (workers != null) {
            workers.shutdown();
            try {
                workers.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            client.disconnect().waitForCompletion(5_000);
            client.close();
        } catch (MqttException e) {
            log.warn("MQTT disconnect failed", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
