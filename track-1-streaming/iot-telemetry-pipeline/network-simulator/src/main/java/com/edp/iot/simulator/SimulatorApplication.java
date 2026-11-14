package com.edp.iot.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * A simulated carrier network. In traffic mode it imports lines, sends their network events to
 * event-ingest and customer requests to customer-api, then reconciles acknowledged usage and request
 * outcomes and webhooks; it exits with 1 when usage was lost or double counted, a request did not
 * end the way the network decided, or a notification went missing, came twice for one change, or
 * arrived unsigned or out of order. In network mode it serves the network's provisioning interface.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SimulatorApplication implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(SimulatorApplication.class);

    private final SimulatorProperties props;
    private int exitCode;

    public SimulatorApplication(SimulatorProperties props) {
        this.props = props;
    }

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(SimulatorApplication.class, args)));
    }

    @Override
    public void run(String... args) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(8))
                .build();
        Registrar registrar = new Registrar(http, props);
        if (props.mode().equals("network")) {
            new NetworkService(props, http, registrar).run();
            return;
        }
        if (props.mode().equals("register")) {
            registrar.registerFleet(); // the two-region simulation imports the same fleet into each region
            return;
        }
        if (props.mode().equals("bench")) {
            new IngestBench(props, http, registrar.networkKey()).run();
            return;
        }
        if (props.mode().equals("reconcile")) {
            reconcileFromLedger(http, registrar);
            return;
        }
        if (props.register()) {
            registrar.registerFleet();
        }
        WebhookReceiver receiver = null;
        if (props.webhooks()) {
            receiver = new WebhookReceiver(props);
            receiver.secrets(registrar.registerWebhooks(receiver.start()));
        }
        java.time.Instant runStart = java.time.Instant.now();
        TrafficEngine engine = new TrafficEngine(props, http, registrar, registrar.networkKey());
        Reconciler reconciler = new Reconciler(http, registrar);
        Map<String, Reconciler.Usage> before = reconciler.read(engine.lines());
        for (SimLine line : engine.lines()) {
            Reconciler.Usage u = before.get(line.iccid);
            if (u != null && !u.lineState().isEmpty()) {
                line.lineState = u.lineState(); // a rerun continues from where the last run left each line
            }
        }
        RequestDriver requests = new RequestDriver(props, http, registrar, engine.lines(), props.seed() + 1);
        requests.start();
        PollProbe poll = props.pollSeconds() > 0 ? new PollProbe(props, http, registrar) : null;
        if (poll != null) {
            poll.start(engine.lines());
        }
        engine.run();
        if (poll != null) {
            poll.stop();
        }
        java.time.Instant trafficEnd = java.time.Instant.now();
        log.info("Ledger of acknowledged events: {}", props.ledgerFile());
        RequestDriver.Report q = requests.finish();
        log.info("Request reconciliation: {} accepted, {} completed, {} failed as the network rejected, {} timed out, "
                        + "{} still pending, {} not matching the network; duplicate keys {} ok / {} wrong; "
                        + "in-progress conflicts {} ok / {} wrong; invalid actions {} ok / {} wrong; {} lines stuck PENDING; "
                        + "202 to COMPLETED p50 {} ms, p95 {} ms. {}",
                q.accepted(), q.completed(), q.failedRejected(), q.failedTimeout(), q.stillPending(), q.mismatched(),
                q.duplicatesMatched(), q.duplicatesWrong(), q.conflictsAsExpected(), q.conflictsWrong(),
                q.invalidAsExpected(), q.invalidWrong(), q.linesStuckPending(), (long) q.p50Ms(), (long) q.p95Ms(),
                q.clean() ? "PASS" : "FAIL");
        exitCode = q.clean() ? 0 : 1;
        if (!props.reconcile()) {
            if (receiver != null) {
                receiver.stop();
            }
            return;
        }
        Reconciler.Report r = reconciler.reconcile(engine.lines(), before, engine.usage(),
                engine.linesWithUnackedUsage(), props.reconcileTimeout());
        log.info("Usage reconciliation: {} lines, {} matched, {} short, {} over, {} uncertain (unacknowledged usage at exit), "
                        + "{} skipped; acknowledged {} bytes, platform counted {} bytes. {}",
                r.lines(), r.matched(), r.short_(), r.over(), r.uncertain(), r.skipped(), r.ledgerBytes(), r.platformBytes(),
                r.clean() ? "PASS" : "FAIL");
        exitCode = r.clean() && exitCode == 0 ? 0 : 1;
        if (receiver == null) {
            return;
        }
        NotificationCheck.Report n = new NotificationCheck(props, http, registrar, receiver)
                .run(runStart, trafficEnd, engine.lines(), before, requests.tracked(), engine.outageWindow(), engine.outageLines());
        if (poll != null) {
            String[] behaviours = props.webhookBehaviours().split(",");
            StringBuilder ok = new StringBuilder();
            for (int c = 0; c < props.customers(); c++) {
                if (behaviours[c % behaviours.length].trim().equals("ok")) {
                    ok.append(Registrar.customerId(c)).append(' ');
                }
            }
            poll.report(receiver.accepted(), ok.toString());
        }
        receiver.stop();
        log.info("Notification reconciliation: {} notifications ({} delivered, {} parked, {} pending), {} received "
                        + "({} more than once), {} refused by failing endpoints; {} delivered but not received, {} received but "
                        + "unknown, {} stuck pending, {} delivered to a dead endpoint, {} bad signatures, {} out of order per line; "
                        + "requests {} checked / {} wrong; usage thresholds {} sent / {} wrong; outage notices {} sent / {} wrong, "
                        + "{} line alerts during the outage; event to webhook p50 {} ms, p95 {} ms. {}",
                n.notifications(), n.delivered(), n.parked(), n.pending(), n.received(), n.duplicatesReceived(), n.refused(),
                n.deliveredNotReceived(), n.receivedUnknown(), n.stuckPending(), n.deadDelivered(), n.badSignatures(),
                n.outOfOrder(), n.requestsChecked(), n.requestsWrong(), n.thresholdsSent(), n.thresholdsWrong(),
                n.outageNotices(), n.outageNoticesWrong(), n.alertsDuringOutage(), (long) n.p50Ms(), (long) n.p95Ms(),
                n.clean() ? "PASS" : "FAIL");
        exitCode = n.clean() && exitCode == 0 ? 0 : 1;
    }

    /**
     * Compares one region's usage totals with the ledger file a traffic run wrote, so the same run can
     * be checked against every region (part 5). Assumes the region's database was fresh for that run.
     */
    private void reconcileFromLedger(HttpClient http, Registrar registrar) throws Exception {
        UsageLedger ledger = new UsageLedger();
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of(props.ledgerFile()))) {
            String row;
            while ((row = reader.readLine()) != null) {
                String[] f = row.split(",");
                if (f.length >= 4 && Long.parseLong(f[3]) > 0) {
                    ledger.acked(f[0], f[1], Long.parseLong(f[3]));
                }
            }
        }
        TrafficEngine engine = new TrafficEngine(props, http, registrar, "");
        Reconciler reconciler = new Reconciler(http, registrar);
        Map<String, Reconciler.Usage> zero = new java.util.HashMap<>();
        reconciler.read(engine.lines()).forEach((iccid, u) ->
                zero.put(iccid, new Reconciler.Usage(u.cycleStart(), 0, u.lineState())));
        Reconciler.Report r = reconciler.reconcile(engine.lines(), zero, ledger, java.util.Set.of(), props.reconcileTimeout());
        log.info("Usage reconciliation at {}: {} lines, {} matched, {} short, {} over, {} skipped; acknowledged {} bytes, "
                        + "platform counted {} bytes. {}", props.apiUrl(), r.lines(), r.matched(), r.short_(), r.over(),
                r.skipped(), r.ledgerBytes(), r.platformBytes(), r.clean() ? "PASS" : "FAIL");
        exitCode = r.clean() ? 0 : 1;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
