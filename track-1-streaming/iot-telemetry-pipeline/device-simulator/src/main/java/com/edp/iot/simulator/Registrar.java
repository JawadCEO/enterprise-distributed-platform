package com.edp.iot.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the simulated fleet through device-status-api. Device secrets are derived from the
 * seed and device ID, so a rerun with the same seed reuses the fleet it registered before.
 */
final class Registrar {

    private static final Logger log = LoggerFactory.getLogger(Registrar.class);
    private static final String[] TYPES = {"TRACKER", "METER", "SENSOR"};
    private static final int BATCH = 500;

    private final HttpClient http;
    private final SimulatorProperties props;

    Registrar(HttpClient http, SimulatorProperties props) {
        this.http = http;
        this.props = props;
    }

    static String tenantId(int index) {
        return String.format("tenant-%03d", index + 1);
    }

    static String deviceId(int index) {
        return String.format("dev-%09d", index + 1);
    }

    String secret(String deviceId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((props.seed() + ":" + deviceId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    void registerFleet() throws Exception {
        int perTenantRate = (int) Math.ceil(3.0 * props.devices() / props.tenants() / props.heartbeatSeconds()) + 100;
        for (int t = 0; t < props.tenants(); t++) {
            Map<String, Object> tenant = Map.of("tenantId", tenantId(t), "name", "Simulated tenant " + (t + 1),
                    "rateLimitPerSec", perTenantRate);
            HttpResponse<String> response = post("/v1/tenants", null, Jsons.write(tenant));
            if (response.statusCode() != 201 && response.statusCode() != 409) {
                throw new IllegalStateException("Tenant registration failed: " + response.statusCode() + " " + response.body());
            }
        }

        int created = 0;
        int existing = 0;
        for (int t = 0; t < props.tenants(); t++) {
            List<Map<String, Object>> batch = new ArrayList<>();
            for (int d = t; d < props.devices(); d += props.tenants()) {
                String id = deviceId(d);
                Map<String, Object> device = new LinkedHashMap<>();
                device.put("deviceId", id);
                device.put("deviceType", TYPES[d % TYPES.length]);
                device.put("firmware", "4.2.1");
                device.put("heartbeatSeconds", props.heartbeatSeconds());
                device.put("secret", secret(id));
                batch.add(device);
                if (batch.size() == BATCH) {
                    int[] counts = sendBatch(tenantId(t), batch);
                    created += counts[0];
                    existing += counts[1];
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                int[] counts = sendBatch(tenantId(t), batch);
                created += counts[0];
                existing += counts[1];
            }
        }
        log.info("Fleet ready: {} tenants, {} devices created, {} already registered", props.tenants(), created, existing);
    }

    private int[] sendBatch(String tenantId, List<Map<String, Object>> batch) throws Exception {
        HttpResponse<String> response = post("/v1/devices/batch", tenantId, Jsons.write(batch));
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Device registration failed: " + response.statusCode() + " " + response.body());
        }
        var body = Jsons.read(response.body());
        return new int[]{body.path("created").size(), body.path("existing").asInt()};
    }

    private HttpResponse<String> post(String path, String tenantId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(props.statusApiUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        request.header("Authorization", "ApiKey " + props.operatorKey());
        if (tenantId != null) {
            request.header("X-Tenant-Id", tenantId);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
