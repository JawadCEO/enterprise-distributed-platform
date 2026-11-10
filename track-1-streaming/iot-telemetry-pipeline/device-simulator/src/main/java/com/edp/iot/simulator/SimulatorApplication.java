package com.edp.iot.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SimulatorApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulatorApplication.class);

    private final SimulatorProperties props;

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
        TrafficEngine engine = new TrafficEngine(props, http, registrar);
        engine.run();
        log.info("Ledger of acknowledged events: {}", props.ledgerFile());
    }
}
