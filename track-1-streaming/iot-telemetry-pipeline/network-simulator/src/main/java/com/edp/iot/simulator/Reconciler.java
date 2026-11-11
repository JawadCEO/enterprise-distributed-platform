package com.edp.iot.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Checks that the platform billed exactly the usage it acknowledged (no lost and no double-counted
 * bytes). Before traffic starts it reads each line's usage this cycle; at the end it waits for the
 * pipeline to catch up and compares the growth with the ledger, line by line.
 */
final class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    record Usage(String cycleStart, long bytesUsed, String lineState) {
    }

    record Report(int lines, int matched, int short_, int over, int uncertain, int skipped, long ledgerBytes,
                  long platformBytes) {
        boolean clean() {
            return short_ == 0 && over == 0;
        }
    }

    private final HttpClient http;
    private final Registrar registrar;
    private final Semaphore concurrency = new Semaphore(50);

    Reconciler(HttpClient http, Registrar registrar) {
        this.http = http;
        this.registrar = registrar;
    }

    Map<String, Usage> read(List<SimLine> lines) {
        Map<String, Usage> out = new ConcurrentHashMap<>();
        List<CompletableFuture<?>> calls = new ArrayList<>();
        for (SimLine line : lines) {
            concurrency.acquireUninterruptibly();
            calls.add(http.sendAsync(registrar.request("/v1/lines/" + line.iccid, line.customerId).GET().build(),
                            HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, error) -> {
                        concurrency.release();
                        if (error == null && response.statusCode() == 200) {
                            JsonNode body = Jsons.read(response.body());
                            JsonNode usage = body.path("usage");
                            out.put(line.iccid, new Usage(usage.path("cycleStart").asText(), usage.path("bytesUsed").asLong(),
                                    body.path("lineState").asText()));
                        }
                    }));
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).exceptionally(e -> null).join();
        return out;
    }

    /**
     * @param uncertain lines that still held unacknowledged usage at exit: the platform may or may
     *                  not have stored those records, so more usage than the ledger is not a fault there
     */
    Report reconcile(List<SimLine> lines, Map<String, Usage> before, UsageLedger ledger, Set<String> uncertain,
                     Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        List<SimLine> pending = new ArrayList<>(lines);
        Map<String, Usage> after = new ConcurrentHashMap<>();
        while (true) {
            after.putAll(read(pending));
            pending.removeIf(l -> delta(before, after, l.iccid) == ledger.bytes(l.iccid) || skipped(before, after, l.iccid));
            if (pending.isEmpty() || System.currentTimeMillis() > deadline) {
                break;
            }
            log.info("Reconciling usage: {} lines not caught up yet", pending.size());
            Thread.sleep(2000);
        }

        int matched = 0, short_ = 0, over = 0, unsure = 0, skipped = 0;
        long ledgerBytes = 0, platformBytes = 0;
        for (SimLine line : lines) {
            if (skipped(before, after, line.iccid)) {
                skipped++;
                continue;
            }
            long expected = ledger.bytes(line.iccid);
            long actual = delta(before, after, line.iccid);
            ledgerBytes += expected;
            platformBytes += actual;
            if (actual == expected) {
                matched++;
            } else if (uncertain.contains(line.iccid) && actual > expected) {
                unsure++;
            } else if (actual < expected) {
                short_++;
                log.warn("Usage short on {}: acknowledged {} bytes, platform counted {}", line.iccid, expected, actual);
            } else {
                over++;
                log.warn("Usage over on {}: acknowledged {} bytes, platform counted {}", line.iccid, expected, actual);
            }
        }
        return new Report(lines.size(), matched, short_, over, unsure, skipped, ledgerBytes, platformBytes);
    }

    private static long delta(Map<String, Usage> before, Map<String, Usage> after, String iccid) {
        Usage a = after.get(iccid);
        Usage b = before.get(iccid);
        return a == null || b == null ? -1 : a.bytesUsed() - b.bytesUsed();
    }

    /**
     * No reading; the billing cycle rolled over during the run; or the line was deactivated, after
     * which usage the ingest cache still accepted is dead-lettered, not billed. Those don't compare.
     */
    private static boolean skipped(Map<String, Usage> before, Map<String, Usage> after, String iccid) {
        Usage a = after.get(iccid);
        Usage b = before.get(iccid);
        return b == null || (a != null && (!a.cycleStart().equals(b.cycleStart()) || "DEACTIVATED".equals(a.lineState())));
    }
}
