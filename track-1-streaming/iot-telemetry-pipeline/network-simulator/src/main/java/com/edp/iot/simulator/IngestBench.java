package com.edp.iot.simulator;

import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP vs MQTT test (Phase 0 PoC, ADR-003). Sends DEVICE_STATUS events for {@code lines} devices at a
 * fixed total rate over one protocol and counts what the sender saw acknowledged against what
 * event-ingest says it accepted into Kafka.
 * <ul>
 *   <li>{@code http}: each device posts each event on its own, as a device talking HTTP directly would;</li>
 *   <li>{@code http-batch}: a gateway posts the same events 100 at a time (how part 5 reached 5,000/s);</li>
 *   <li>{@code mqtt}: each device holds its own MQTT connection and publishes each event at QoS 1.</li>
 * </ul>
 * At {@code benchDisconnectAt} a share of the devices lose their connection at once without saying
 * goodbye and come back {@code benchReconnectAfter} later, all together, keeping what they could not
 * send meanwhile.
 */
final class IngestBench {

    private static final Logger log = LoggerFactory.getLogger(IngestBench.class);
    private static final Pattern ACCEPTED = Pattern.compile("iot_ingest_events_total\\{[^}]*result=\"accepted\"[^}]*} ([0-9.E]+)");

    private final SimulatorProperties props;
    private final HttpClient http;
    private final String networkKey;
    private final long bootId = Instant.now().getEpochSecond();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong acked = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> latenciesDuringStorm = new ConcurrentLinkedQueue<>();
    private volatile boolean storm;
    private final List<MqttAsyncClient> mqtt = new ArrayList<>();
    private final long[] sequence;
    private final boolean[] down;
    private final List<List<String>> buffered = new ArrayList<>();

    IngestBench(SimulatorProperties props, HttpClient http, String networkKey) {
        this.props = props;
        this.http = http;
        this.networkKey = networkKey;
        this.sequence = new long[props.lines()];
        this.down = new boolean[props.lines()];
        for (int i = 0; i < props.lines(); i++) {
            buffered.add(Collections.synchronizedList(new ArrayList<>()));
        }
    }

    void run() throws Exception {
        String protocol = props.benchProtocol();
        int devices = props.lines();
        double before = acceptedSoFar();
        if (protocol.equals("mqtt")) {
            connectAll(devices);
        }
        long start = System.nanoTime();
        long durationNs = props.duration().toNanos();
        long tickNs = 10_000_000L; // 10 ms
        double perTick = props.benchRate() / 100.0;
        double carry = 0;
        int next = 0;
        long disconnectAt = props.benchDisconnectAt().isZero() ? Long.MAX_VALUE : props.benchDisconnectAt().toNanos();
        long reconnectAt = disconnectAt == Long.MAX_VALUE ? Long.MAX_VALUE : disconnectAt + props.benchReconnectAfter().toNanos();
        boolean dropped = false;
        boolean back = false;
        List<Integer> victims = new ArrayList<>();
        long lastLog = start;
        for (long tick = 0; ; tick++) {
            long target = start + tick * tickNs;
            long now = System.nanoTime();
            if (target > now) {
                Thread.sleep((target - now) / 1_000_000, (int) ((target - now) % 1_000_000));
            }
            long elapsed = System.nanoTime() - start;
            if (elapsed >= durationNs) {
                break;
            }
            if (!dropped && elapsed >= disconnectAt) {
                dropped = true;
                storm = true;
                int count = (int) Math.round(devices * props.benchDisconnectShare());
                for (int i = 0; i < count; i++) {
                    victims.add(i * devices / count);
                }
                drop(victims);
            }
            if (dropped && !back && elapsed >= reconnectAt) {
                back = true;
                restore(victims);
            }
            if (back && storm && elapsed >= reconnectAt + 10_000_000_000L) {
                storm = false;
            }
            carry += perTick;
            int n = (int) carry;
            carry -= n;
            List<Integer> batchDevices = new ArrayList<>(n);
            List<String> batch = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                int d = next;
                next = (next + 1) % devices;
                String event = event(d);
                if (down[d]) {
                    buffered.get(d).add(event); // the device keeps it until it is connected again
                    continue;
                }
                batchDevices.add(d);
                batch.add(event);
            }
            send(protocol, batchDevices, batch);
            if (System.nanoTime() - lastLog > 10_000_000_000L) {
                lastLog = System.nanoTime();
                log.info("t={}s sent={} acked={} failed={}", elapsed / 1_000_000_000L, sent.get(), acked.get(), failed.get());
            }
        }
        long waitUntil = System.currentTimeMillis() + 60_000;
        while (acked.get() + failed.get() < sent.get() && System.currentTimeMillis() < waitUntil) {
            Thread.sleep(200);
        }
        Thread.sleep(2_000);
        double accepted = acceptedSoFar() - before;
        double seconds = props.duration().toMillis() / 1000.0;
        log.info("Bench {}: {} devices, {} events/s target; sent {} ({}/s), acknowledged {}, failed {}, unacknowledged {}; "
                        + "event-ingest accepted {} into Kafka; ack p50 {} ms, p95 {} ms, p99 {} ms; "
                        + "during the disconnect and 10 s after it: p95 {} ms over {} events",
                protocol, devices, props.benchRate(), sent.get(), Math.round(sent.get() / seconds), acked.get(), failed.get(),
                sent.get() - acked.get() - failed.get(), Math.round(accepted), pct(latencies, 50), pct(latencies, 95),
                pct(latencies, 99), pct(latenciesDuringStorm, 95), latenciesDuringStorm.size());
        for (MqttAsyncClient c : mqtt) {
            try {
                c.disconnect().waitForCompletion(2_000);
                c.close();
            } catch (Exception ignored) {
                // closing at the end; nothing to keep
            }
        }
    }

    private void send(String protocol, List<Integer> devices, List<String> events) {
        if (events.isEmpty()) {
            return;
        }
        switch (protocol) {
            case "http" -> {
                for (String e : events) {
                    post(List.of(e));
                }
            }
            case "http-batch" -> {
                for (int i = 0; i < events.size(); i += 100) {
                    post(events.subList(i, Math.min(events.size(), i + 100)));
                }
            }
            case "mqtt" -> {
                for (int i = 0; i < events.size(); i++) {
                    publish(devices.get(i), events.get(i));
                }
            }
            default -> throw new IllegalArgumentException("BENCH_PROTOCOL must be http, http-batch or mqtt");
        }
    }

    private void post(List<String> events) {
        String body = "{\"events\":[" + String.join(",", events) + "]}";
        long t0 = System.nanoTime();
        boolean inStorm = storm;
        sent.addAndGet(events.size());
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.ingestUrl() + "/v1/events"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "ApiKey " + networkKey)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (error != null || response.statusCode() != 200) {
                failed.addAndGet(events.size());
                return;
            }
            int ok = 0;
            for (var r : Jsons.read(response.body()).path("results")) {
                if (r.path("status").asText().equals("ACCEPTED")) {
                    ok++;
                }
            }
            acked.addAndGet(ok);
            failed.addAndGet(events.size() - ok);
            record(ms, inStorm);
        });
    }

    private void publish(int device, String event) {
        long t0 = System.nanoTime();
        boolean inStorm = storm;
        sent.incrementAndGet();
        try {
            mqtt.get(device).publish("iot/events/" + Registrar.iccid(device), event.getBytes(), 1, false, null,
                    new IMqttActionListener() {
                        @Override
                        public void onSuccess(IMqttToken token) {
                            acked.incrementAndGet();
                            record((System.nanoTime() - t0) / 1_000_000, inStorm);
                        }

                        @Override
                        public void onFailure(IMqttToken token, Throwable e) {
                            failed.incrementAndGet();
                        }
                    });
        } catch (Exception e) {
            failed.incrementAndGet();
        }
    }

    private void record(long ms, boolean inStorm) {
        latencies.add(ms);
        if (inStorm || storm) {
            latenciesDuringStorm.add(ms);
        }
    }

    private void connectAll(int devices) throws Exception {
        long t0 = System.currentTimeMillis();
        List<IMqttToken> tokens = new ArrayList<>();
        for (int d = 0; d < devices; d++) {
            MqttAsyncClient c = new MqttAsyncClient(props.mqttUrl(), "dev-" + Registrar.iccid(d), new MemoryPersistence());
            DisconnectedBufferOptions buffer = new DisconnectedBufferOptions();
            buffer.setBufferEnabled(true); // a device keeps what it could not send while disconnected
            buffer.setBufferSize(100_000);
            buffer.setPersistBuffer(false);
            buffer.setDeleteOldestMessages(false);
            c.setBufferOpts(buffer);
            mqtt.add(c);
            tokens.add(c.connect(options()));
        }
        for (IMqttToken t : tokens) {
            t.waitForCompletion(30_000);
        }
        log.info("{} MQTT devices connected in {} ms", devices, System.currentTimeMillis() - t0);
    }

    private static MqttConnectOptions options() {
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(false);
        o.setMaxInflight(100);
        o.setKeepAliveInterval(30);
        o.setConnectionTimeout(30);
        return o;
    }

    private void drop(List<Integer> victims) {
        log.info("Dropping {} devices at once", victims.size());
        for (int d : victims) {
            down[d] = true;
            if (!mqtt.isEmpty()) {
                try {
                    mqtt.get(d).disconnectForcibly(0, 0, false); // no DISCONNECT packet: the link just goes
                } catch (Exception ignored) {
                    // already gone
                }
            }
        }
    }

    private void restore(List<Integer> victims) throws Exception {
        long t0 = System.currentTimeMillis();
        List<IMqttToken> tokens = new ArrayList<>();
        if (!mqtt.isEmpty()) {
            for (int d : victims) {
                tokens.add(mqtt.get(d).connect(options()));
            }
            for (IMqttToken t : tokens) {
                t.waitForCompletion(30_000);
            }
        }
        int resent = 0;
        String protocol = props.benchProtocol();
        for (int d : victims) {
            down[d] = false;
            List<String> held = new ArrayList<>(buffered.get(d));
            buffered.get(d).clear();
            resent += held.size();
            List<Integer> who = Collections.nCopies(held.size(), d);
            send(protocol, who, held);
        }
        log.info("{} devices back in {} ms; they sent {} events held while disconnected", victims.size(),
                System.currentTimeMillis() - t0, resent);
    }

    private String event(int d) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("type", "DEVICE_STATUS");
        e.put("lineId", Registrar.iccid(d));
        e.put("imei", Registrar.imei(d));
        e.put("bootId", bootId);
        e.put("sequence", ++sequence[d]);
        e.put("deviceTime", Instant.now().toString());
        e.put("payload", Map.of("batteryPct", 50 + d % 50, "signalDbm", -70 - d % 30));
        return Jsons.write(e);
    }

    /** event-ingest's own count of events it put into Kafka, from its metrics. */
    private double acceptedSoFar() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(props.ingestUrl() + "/actuator/prometheus"))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            Matcher m = ACCEPTED.matcher(r.body());
            double total = 0;
            while (m.find()) {
                total += Double.parseDouble(m.group(1));
            }
            return total;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static long pct(ConcurrentLinkedQueue<Long> values, int p) {
        List<Long> sorted = new ArrayList<>(values);
        if (sorted.isEmpty()) {
            return 0;
        }
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
