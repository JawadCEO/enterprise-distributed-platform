package com.edp.iot.simulator;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * One simulated device. It follows the device contract (FR-22): events stay in its buffer until
 * acknowledged, {@code bootId} goes up on every reboot, and {@code sequence} restarts at 0 then.
 */
final class SimDevice {

    record Event(String json, long bootId, long sequence) {
    }

    final String deviceId;
    final String tenantId;
    final String secret;
    final int heartbeatMillis;
    final boolean noisy;

    /**
     * A real device keeps its boot counter across power cycles. The simulator does not persist it,
     * so each run starts from the run's start time in seconds: a new run looks like a reboot of every
     * device instead of a replay of the last run's (bootId, sequence) pairs, which the pipeline
     * would correctly drop as duplicates.
     */
    static final long RUN_BOOT_BASE = java.time.Instant.now().getEpochSecond();

    long bootId = RUN_BOOT_BASE;
    long sequence;
    long nextDueAt;
    long backoffUntil;
    long silentUntil;
    int consecutiveFailures;
    boolean inFlight;
    final Deque<Event> buffer = new ArrayDeque<>();
    Event lastAcked;

    private double battery;
    private final double signalBaseline;
    private boolean fault;

    SimDevice(String deviceId, String tenantId, String secret, int heartbeatMillis, boolean noisy, Random random) {
        this.deviceId = deviceId;
        this.tenantId = tenantId;
        this.secret = secret;
        this.heartbeatMillis = heartbeatMillis;
        this.noisy = noisy;
        this.battery = 40 + random.nextDouble() * 60;
        this.signalBaseline = -60 - random.nextDouble() * 40;
    }

    void reboot() {
        bootId++;
        sequence = 0;
        fault = false;
    }

    /** Builds the next event. Most are telemetry, some heartbeats, a few status reports. */
    Event nextEvent(Random random, long nowMillis, double clockSkewRate, double faultFactor) {
        battery -= 0.05 + random.nextDouble() * 0.1;
        if (battery < 5 || random.nextDouble() < 0.002) {
            battery = 100;
        }
        double signal = Math.max(-150, Math.min(-30, signalBaseline + random.nextGaussian() * 6));
        double hour = (nowMillis / 3_600_000.0) % 24;
        double temperature = 20 + 8 * Math.sin(2 * Math.PI * hour / 24) + random.nextGaussian();

        double roll = random.nextDouble();
        String type = roll < 0.70 ? "TELEMETRY" : roll < 0.95 ? "HEARTBEAT" : "STATUS";
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (type.equals("TELEMETRY")) {
            metrics.put("batteryPct", Math.round(battery));
            metrics.put("signalDbm", Math.round(signal));
            metrics.put("temperatureC", Math.round(temperature * 10) / 10.0);
            metrics.put("firmware", "4.2.1");
        } else if (type.equals("STATUS")) {
            if (!fault && random.nextDouble() < 0.1 * faultFactor) { // the clean scenario (factor 0) reports no faults
                fault = true;
            } else if (fault && random.nextDouble() < 0.5) {
                fault = false;
            }
            if (fault) {
                metrics.put("faultCode", "E42");
            }
        }

        Instant deviceTime = Instant.ofEpochMilli(nowMillis);
        if (random.nextDouble() < clockSkewRate) {
            // a wrong device clock: mostly behind (accepted), sometimes far ahead (rejected)
            deviceTime = random.nextDouble() < 0.8 ? deviceTime.minusSeconds(3 * 3600) : deviceTime.plusSeconds(2 * 86400);
        }

        long seq = sequence++;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("deviceId", deviceId);
        event.put("bootId", bootId);
        event.put("sequence", seq);
        event.put("deviceTime", deviceTime.toString());
        event.put("type", type);
        event.put("metrics", metrics);
        return new Event(Jsons.write(event), bootId, seq);
    }
}
