package com.edp.iot.simulator;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Counters and request-latency percentiles, printed every few seconds and at the end. */
final class Stats {

    final LongAdder generated = new LongAdder();
    final LongAdder accepted = new LongAdder();
    final LongAdder rejected = new LongAdder();
    final LongAdder rateLimited = new LongAdder();
    final LongAdder retried = new LongAdder();
    final LongAdder requestErrors = new LongAdder();
    final LongAdder duplicatesSent = new LongAdder();
    final LongAdder malformedSent = new LongAdder();
    final LongAdder unknownLineSent = new LongAdder();
    final LongAdder reboots = new LongAdder();
    final LongAdder silences = new LongAdder();
    final LongAdder detaches = new LongAdder();
    final LongAdder simMoves = new LongAdder();
    final Map<String, LongAdder> rejectCodes = new ConcurrentHashMap<>();

    private final long[] latencies = new long[100_000];
    private final AtomicInteger latencyCount = new AtomicInteger();

    void recordLatency(long micros) {
        int i = latencyCount.getAndIncrement();
        latencies[Math.floorMod(i, latencies.length)] = micros;
    }

    void rejected(String code) {
        rejected.increment();
        rejectCodes.computeIfAbsent(code == null || code.isEmpty() ? "UNKNOWN" : code, k -> new LongAdder()).increment();
    }

    /** p50, p95, p99 in milliseconds over the most recent requests. */
    synchronized double[] percentiles() {
        int n = Math.min(latencyCount.get(), latencies.length);
        if (n == 0) {
            return new double[]{0, 0, 0};
        }
        long[] copy = Arrays.copyOf(latencies, n);
        Arrays.sort(copy);
        return new double[]{copy[(int) (n * 0.50)] / 1000.0, copy[Math.min(n - 1, (int) (n * 0.95))] / 1000.0,
                copy[Math.min(n - 1, (int) (n * 0.99))] / 1000.0};
    }

    String line(double seconds) {
        double[] p = percentiles();
        return String.format("t=%.0fs generated=%d accepted=%d (%.0f/s) rejected=%d rateLimited=%d retry=%d errors=%d "
                        + "dupes=%d malformed=%d unknownLine=%d reboots=%d silences=%d detaches=%d simMoves=%d "
                        + "ack p50=%.1fms p95=%.1fms p99=%.1fms",
                seconds, generated.sum(), accepted.sum(), seconds > 0 ? accepted.sum() / seconds : 0, rejected.sum(),
                rateLimited.sum(), retried.sum(), requestErrors.sum(), duplicatesSent.sum(), malformedSent.sum(),
                unknownLineSent.sum(), reboots.sum(), silences.sum(), detaches.sum(), simMoves.sum(), p[0], p[1], p[2]);
    }

    String rejectCodes() {
        Map<String, Long> sorted = new TreeMap<>();
        rejectCodes.forEach((k, v) -> sorted.put(k, v.sum()));
        return sorted.toString();
    }
}
