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
 * A simulated carrier network: imports lines, sends their network events to event-ingest, then
 * reconciles acknowledged usage against what customer-api reports. Exits with 1 when usage was
 * lost or double counted.
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
        if (props.register()) {
            registrar.registerFleet();
        }
        TrafficEngine engine = new TrafficEngine(props, http, registrar, registrar.networkKey());
        Reconciler reconciler = new Reconciler(http, registrar);
        Map<String, Reconciler.Usage> before = props.reconcile() ? reconciler.read(engine.lines()) : Map.of();
        engine.run();
        log.info("Ledger of acknowledged events: {}", props.ledgerFile());
        if (!props.reconcile()) {
            return;
        }
        Reconciler.Report r = reconciler.reconcile(engine.lines(), before, engine.usage(),
                engine.linesWithUnackedUsage(), props.reconcileTimeout());
        log.info("Usage reconciliation: {} lines, {} matched, {} short, {} over, {} uncertain (unacknowledged usage at exit), "
                        + "{} skipped; acknowledged {} bytes, platform counted {} bytes. {}",
                r.lines(), r.matched(), r.short_(), r.over(), r.uncertain(), r.skipped(), r.ledgerBytes(), r.platformBytes(),
                r.clean() ? "PASS" : "FAIL");
        exitCode = r.clean() ? 0 : 1;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
