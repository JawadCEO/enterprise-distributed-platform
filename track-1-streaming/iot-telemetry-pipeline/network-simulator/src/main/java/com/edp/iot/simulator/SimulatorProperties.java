package com.edp.iot.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Simulator settings. {@code scenario} applies a preset on top of these (see {@link Scenario}).
 * Rates are probabilities per line tick unless stated otherwise.
 */
@ConfigurationProperties("iot.sim")
public record SimulatorProperties(
        @DefaultValue("http://localhost:8080") String ingestUrl,
        @DefaultValue("http://localhost:8084") String apiUrl,
        /* Operator API key, used to import the simulated lines and to read them back for reconciliation. */
        @DefaultValue("") String operatorKey,
        /* Network key for event-ingest. When empty, one is issued through customer-api at start. */
        @DefaultValue("") String networkKey,
        @DefaultValue("steady") String scenario,
        @DefaultValue("5") int customers,
        @DefaultValue("1000") int lines,
        /* How often a line reports in. Lines are imported with a keep-alive window of twice this. */
        @DefaultValue("10") int tickSeconds,
        @DefaultValue("300s") Duration duration,
        @DefaultValue("42") long seed,
        @DefaultValue("200") int maxConcurrentRequests,
        @DefaultValue("100") int maxBatch,
        @DefaultValue("true") boolean register,
        @DefaultValue("true") boolean reconcile,
        @DefaultValue("120s") Duration reconcileTimeout,
        @DefaultValue("ack-ledger.csv") String ledgerFile,
        @DefaultValue("0.15") double usageRate,
        @DefaultValue("0.35") double statusRate,
        @DefaultValue("0.01") double duplicateRate,
        @DefaultValue("0.01") double outOfOrderRate,
        @DefaultValue("0.005") double malformedRate,
        @DefaultValue("0.001") double unknownLineRate,
        @DefaultValue("0.005") double clockSkewRate,
        @DefaultValue("0.0005") double rebootRate,
        @DefaultValue("0.0005") double silenceRate,
        @DefaultValue("0.0005") double detachRate,
        @DefaultValue("0.0002") double simMoveRate,
        @DefaultValue("1") int noisyCustomerMultiplier) {
}
