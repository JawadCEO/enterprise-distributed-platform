package com.edp.iot.simulator;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * One simulated line: a SIM in a device on the carrier network. The network reports for it in
 * order: events stay in its buffer until acknowledged, the device's {@code bootId} goes up on every
 * reboot and {@code sequence} restarts at 0 then. A SIM move swaps in a new device (a new IMEI with
 * its own boot counter).
 */
final class SimLine {

    /** One event as sent. {@code usageBytes} is non-zero only for a usage record. */
    record Event(String eventId, String type, String json, long usageBytes) {
    }

    final int index;
    final String iccid;
    final String customerId;
    final int tickMillis;
    final boolean noisy;

    /**
     * A real device keeps its boot counter across power cycles. The simulator does not persist it,
     * so each run starts from the run's start time in seconds: a new run looks like a reboot of every
     * device instead of a replay of the last run's (bootId, sequence) pairs, which the pipeline
     * would correctly drop as stale.
     */
    static final long RUN_BOOT_BASE = Instant.now().getEpochSecond();

    /** The line state as the simulator last learned it from customer-api; only live lines send events. */
    volatile String lineState = "ACTIVE";
    String imei;
    long bootId = RUN_BOOT_BASE;
    long sequence;
    boolean attached;
    long detachedUntil;
    long nextDueAt;
    long backoffUntil;
    long silentUntil;
    int consecutiveFailures;
    int simMoves;
    boolean inFlight;
    final Deque<Event> buffer = new ArrayDeque<>();
    Event lastAcked;

    private double battery;
    private final double signalBaseline;
    private boolean fault;

    SimLine(int index, String customerId, int tickMillis, boolean noisy, Random random) {
        this.index = index;
        this.iccid = Registrar.iccid(index);
        this.imei = Registrar.imei(index);
        this.customerId = customerId;
        this.tickMillis = tickMillis;
        this.noisy = noisy;
        this.battery = 40 + random.nextDouble() * 60;
        this.signalBaseline = -60 - random.nextDouble() * 40;
    }

    boolean live() {
        String s = lineState;
        return s.equals("ACTIVE") || s.equals("SUSPENDED") || s.equals("PENDING");
    }

    void reboot() {
        bootId++;
        sequence = 0;
        fault = false;
    }

    Event attach(long nowMillis) {
        attached = true;
        return network("DEVICE_ATTACHED", nowMillis, Map.of("region", "local"), imei, 0);
    }

    Event detach(long nowMillis, long untilMillis) {
        attached = false;
        detachedUntil = untilMillis;
        return network("DEVICE_DETACHED", nowMillis, Map.of(), null, 0);
    }

    /** The SIM shows up in another device: a new IMEI with a boot counter of its own. */
    Event moveSim(long nowMillis, Random random) {
        simMoves++;
        imei = String.format("36%013d", (long) index * 100 + simMoves % 100);
        bootId = RUN_BOOT_BASE + random.nextInt(1000);
        sequence = 0;
        return network("IMEI_CHANGED", nowMillis, Map.of(), imei, 0);
    }

    Event usage(long nowMillis, Random random) {
        long up = 1024 + random.nextInt(50 * 1024);
        long down = 1024 + random.nextInt(200 * 1024);
        return network("USAGE_RECORD", nowMillis, Map.of("bytesUp", up, "bytesDown", down), null, up + down);
    }

    /** A keep-alive or a status report from the device itself, ordered by (bootId, sequence). */
    Event deviceEvent(Random random, long nowMillis, boolean status, double clockSkewRate, double faultFactor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String type = "KEEP_ALIVE";
        if (status) {
            type = "DEVICE_STATUS";
            battery -= 0.05 + random.nextDouble() * 0.1;
            if (battery < 5 || random.nextDouble() < 0.002) {
                battery = 100;
            }
            double signal = Math.max(-150, Math.min(-30, signalBaseline + random.nextGaussian() * 6));
            double hour = (nowMillis / 3_600_000.0) % 24;
            double temperature = 20 + 8 * Math.sin(2 * Math.PI * hour / 24) + random.nextGaussian();
            payload.put("batteryPct", Math.round(battery));
            payload.put("signalDbm", Math.round(signal));
            payload.put("temperatureC", Math.round(temperature * 10) / 10.0);
            payload.put("firmware", "4.2.1");
            if (!fault && random.nextDouble() < 0.02 * faultFactor) { // the clean scenario (factor 0) reports no faults
                fault = true;
            } else if (fault && random.nextDouble() < 0.3) {
                fault = false;
            }
            if (fault) {
                payload.put("faultCode", "E42");
            }
        }

        Instant deviceTime = Instant.ofEpochMilli(nowMillis);
        if (random.nextDouble() < clockSkewRate) {
            // a wrong device clock: mostly behind (accepted), sometimes far ahead (rejected)
            deviceTime = random.nextDouble() < 0.8 ? deviceTime.minusSeconds(3 * 3600) : deviceTime.plusSeconds(2 * 86400);
        }
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("type", type);
        event.put("lineId", iccid);
        event.put("imei", imei);
        event.put("bootId", bootId);
        event.put("sequence", sequence++);
        event.put("deviceTime", deviceTime.toString());
        event.put("payload", payload);
        return new Event(eventId, type, Jsons.write(event), 0);
    }

    /** A status report the platform must reject: a battery level that is not a number. */
    String malformed(long nowMillis) {
        return Jsons.write(Map.of("eventId", UUID.randomUUID().toString(), "type", "DEVICE_STATUS", "lineId", iccid,
                "imei", imei, "bootId", bootId, "sequence", sequence, "deviceTime", Instant.ofEpochMilli(nowMillis).toString(),
                "payload", Map.of("batteryPct", "low")));
    }

    private Event network(String type, long nowMillis, Map<String, Object> payload, String eventImei, long usageBytes) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("type", type);
        event.put("lineId", iccid);
        if (eventImei != null) {
            event.put("imei", eventImei);
        }
        event.put("networkTime", Instant.ofEpochMilli(nowMillis).toString());
        event.put("payload", payload);
        return new Event(eventId, type, Jsons.write(event), usageBytes);
    }
}
