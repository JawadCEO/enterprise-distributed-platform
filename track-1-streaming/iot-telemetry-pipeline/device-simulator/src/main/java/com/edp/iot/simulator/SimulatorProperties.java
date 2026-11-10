package com.edp.iot.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Simulator settings. {@code scenario} applies a preset on top of these (see {@link Scenario}).
 * Rates are probabilities per generated event unless stated otherwise.
 */
@ConfigurationProperties("iot.sim")
public record SimulatorProperties(
        @DefaultValue("http://localhost:8080") String gatewayUrl,
        @DefaultValue("http://localhost:8084") String statusApiUrl,
        /* Operator API key used to register the simulated fleet. */
        @DefaultValue("") String operatorKey,
        @DefaultValue("steady") String scenario,
        @DefaultValue("5") int tenants,
        @DefaultValue("1000") int devices,
        @DefaultValue("10") int heartbeatSeconds,
        @DefaultValue("300s") Duration duration,
        @DefaultValue("42") long seed,
        @DefaultValue("200") int maxConcurrentRequests,
        @DefaultValue("100") int maxBatch,
        @DefaultValue("true") boolean register,
        @DefaultValue("ack-ledger.csv") String ledgerFile,
        @DefaultValue("0.01") double duplicateRate,
        @DefaultValue("0.01") double outOfOrderRate,
        @DefaultValue("0.005") double malformedRate,
        @DefaultValue("0.001") double unknownDeviceRate,
        @DefaultValue("0.005") double clockSkewRate,
        @DefaultValue("0.0005") double rebootRate,
        @DefaultValue("0.0005") double silenceRate,
        @DefaultValue("1") int noisyTenantMultiplier) {
}
