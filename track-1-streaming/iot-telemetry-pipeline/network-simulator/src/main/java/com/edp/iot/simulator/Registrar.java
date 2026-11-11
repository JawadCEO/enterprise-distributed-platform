package com.edp.iot.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sets up the simulated carrier accounts through customer-api, the way an operator loads a batch
 * of SIMs: customers, then their lines imported already {@code ACTIVE}, then a network key for
 * event-ingest. ICCIDs and IMEIs come from the line index, so a rerun reuses the same lines.
 */
final class Registrar {

    private static final Logger log = LoggerFactory.getLogger(Registrar.class);
    static final String[] SIM_TYPES = {"REMOVABLE", "EMBEDDED", "INTEGRATED"};
    static final String[] DEVICE_KINDS = {"TRACKER", "METER", "FRIDGE_SENSOR"};
    private static final int BATCH = 1000;

    private final HttpClient http;
    private final SimulatorProperties props;

    Registrar(HttpClient http, SimulatorProperties props) {
        this.http = http;
        this.props = props;
    }

    static String customerId(int index) {
        return String.format("customer-%03d", index + 1);
    }

    /** 19 digits: the 89 telecom prefix, a made-up issuer 014, then the line number. */
    static String iccid(int index) {
        return String.format("89014%014d", index + 1);
    }

    static String imei(int index) {
        return String.format("35%013d", index + 1);
    }

    /** Mostly removable SIMs, like a real IoT fleet. {@code slot} is the line's position within its customer. */
    static String simType(int slot) {
        int r = slot % 10;
        return r < 6 ? SIM_TYPES[0] : r < 9 ? SIM_TYPES[1] : SIM_TYPES[2];
    }

    static String deviceKind(int index) {
        return DEVICE_KINDS[index % DEVICE_KINDS.length];
    }

    /** Lines kept in INVENTORY at import, so activate requests have something to work on. */
    boolean inventory(int slot) {
        int every = props.inventoryShare() <= 0 ? Integer.MAX_VALUE : (int) Math.round(1 / props.inventoryShare());
        return slot % every == every - 1;
    }

    /** Keep-alive window the lines are imported with: two ticks, so one late report is not an outage. */
    int keepAliveSeconds() {
        return Math.max(1, 2 * props.tickSeconds());
    }

    void registerFleet() throws Exception {
        int perCustomerRate = (int) Math.ceil(3.0 * props.lines() / props.customers() / props.tickSeconds()) + 100;
        for (int c = 0; c < props.customers(); c++) {
            Map<String, Object> customer = new LinkedHashMap<>();
            customer.put("customerId", customerId(c));
            customer.put("name", "Simulated customer " + (c + 1));
            customer.put("rateLimitPerSec", perCustomerRate);
            // the first customer opted in to suspension when an embedded SIM moves to another device
            customer.put("autoSuspendOnSimMove", c == 0);
            HttpResponse<String> response = send("POST", "/v1/customers", null, Jsons.write(customer));
            if (response.statusCode() != 201 && response.statusCode() != 409) {
                throw new IllegalStateException("Customer import failed: " + response.statusCode() + " " + response.body());
            }
        }

        int created = 0;
        int existing = 0;
        for (int c = 0; c < props.customers(); c++) {
            List<Map<String, Object>> batch = new ArrayList<>();
            for (int i = c; i < props.lines(); i += props.customers()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("iccid", iccid(i));
                line.put("simType", simType(i / props.customers()));
                line.put("deviceKind", deviceKind(i));
                line.put("keepAliveSeconds", keepAliveSeconds());
                line.put("planCode", "IOT-STANDARD");
                line.put("dataAllowanceBytes", 50L * 1024 * 1024);
                line.put("initialState", inventory(i / props.customers()) ? "INVENTORY" : "ACTIVE");
                batch.add(line);
                if (batch.size() == BATCH) {
                    int[] counts = sendBatch(customerId(c), batch);
                    created += counts[0];
                    existing += counts[1];
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                int[] counts = sendBatch(customerId(c), batch);
                created += counts[0];
                existing += counts[1];
            }
        }
        log.info("Lines ready: {} customers, {} lines imported, {} already there", props.customers(), created, existing);
    }

    String networkKey() throws Exception {
        if (!props.networkKey().isBlank()) {
            return props.networkKey();
        }
        HttpResponse<String> response = send("POST", "/v1/network-keys", null, "{}");
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Network key request failed: " + response.statusCode() + " " + response.body());
        }
        return Jsons.read(response.body()).path("apiKey").asText();
    }

    private int[] sendBatch(String customerId, List<Map<String, Object>> batch) throws Exception {
        HttpResponse<String> response = send("POST", "/v1/lines/batch", customerId, Jsons.write(batch));
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Line import failed: " + response.statusCode() + " " + response.body());
        }
        var body = Jsons.read(response.body());
        return new int[]{body.path("created").asInt(), body.path("existing").asInt()};
    }

    private HttpResponse<String> send(String method, String path, String customerId, String body) throws Exception {
        return http.send(request(path, customerId).method(method, HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A request to customer-api with the operator key, acting for {@code customerId} when given. */
    HttpRequest.Builder request(String path, String customerId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(props.apiUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "ApiKey " + props.operatorKey());
        if (customerId != null) {
            request.header("X-Customer-Id", customerId);
        }
        return request;
    }
}
