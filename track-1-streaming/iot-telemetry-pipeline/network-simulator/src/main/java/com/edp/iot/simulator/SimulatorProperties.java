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
        /* traffic: import lines, send events and requests, reconcile. network: run the simulated
           carrier network's provisioning interface until stopped. */
        @DefaultValue("traffic") String mode,
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
        /* Load benchmark (part 5): above 0, one sender packs up to this many events from many lines into each
           request, the way a network element or device gateway batches, instead of one request per line.
           event-ingest takes at most 100 events per request. */
        @DefaultValue("0") int gatewayBatch,
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
        @DefaultValue("1") int noisyCustomerMultiplier,
        /* Customer requests per second across all lines (0 turns them off). */
        @DefaultValue("2") double requestRate,
        @DefaultValue("0.05") double duplicateRequestRate,
        @DefaultValue("0.05") double conflictingRequestRate,
        @DefaultValue("0.03") double invalidRequestRate,
        @DefaultValue("0.02") double deactivateShare,
        /* Share of lines imported as INVENTORY, waiting for an activate request. */
        @DefaultValue("0.05") double inventoryShare,
        /* How long to wait at the end for every request to finish: the request time limit plus margin. */
        @DefaultValue("180s") Duration requestWait,
        /* The simulated network (network mode serves it; traffic mode reads its ledger). */
        @DefaultValue("http://localhost:8090") String networkUrl,
        @DefaultValue("8090") int networkPort,
        @DefaultValue("1s") Duration networkLatency,
        @DefaultValue("0.05") double networkRejectRate,
        @DefaultValue("0.05") double networkUnavailableRate,
        @DefaultValue("0.01") double networkDropRate,
        /* Webhooks (part 2c): each customer registers an endpoint served by this simulator. */
        @DefaultValue("true") boolean webhooks,
        @DefaultValue("8091") int webhookPort,
        /* Host the platform reaches the simulator on; empty means this container's own address. */
        @DefaultValue("") String webhookHost,
        /* How each customer's endpoint behaves, by customer number in turn: ok answers at once, flaky
           fails 30% of requests, late is down for the first 60 s, slow takes 2 s, dead always fails. */
        @DefaultValue("ok,flaky,late,slow,dead") String webhookBehaviours,
        /* Lines imported with a small data allowance (one in this many), so usage thresholds are crossed. */
        @DefaultValue("20") int smallAllowanceEvery,
        @DefaultValue("262144") long smallAllowanceBytes,
        /* The outage scenario silences every line in this region for a while. */
        @DefaultValue("south") String outageRegion,
        @DefaultValue("150s") Duration outageStart,
        @DefaultValue("90s") Duration outageLength,
        /* How long to wait at the end for notifications to be delivered. */
        @DefaultValue("120s") Duration notificationWait,
        /* Polling vs push test: also poll every line's state once per this many seconds; 0 is off. */
        @DefaultValue("0") int pollSeconds,
        /* HTTP vs MQTT test (bench mode): http, http-batch or mqtt, the total event rate, and a share of
           devices that drop at once at benchDisconnectAt (0 is never) and return benchReconnectAfter later. */
        @DefaultValue("http-batch") String benchProtocol,
        @DefaultValue("1000") int benchRate,
        @DefaultValue("0s") Duration benchDisconnectAt,
        @DefaultValue("0.1") double benchDisconnectShare,
        @DefaultValue("5s") Duration benchReconnectAfter,
        @DefaultValue("tcp://localhost:1883") String mqttUrl) {
}
