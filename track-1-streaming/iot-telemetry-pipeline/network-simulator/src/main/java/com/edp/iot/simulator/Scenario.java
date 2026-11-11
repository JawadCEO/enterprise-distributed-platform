package com.edp.iot.simulator;

/**
 * Named, repeatable traffic presets. Failure drills (broker kill, ingest outage) use {@code steady}
 * traffic while the operator breaks something; the scenario only shapes the traffic.
 */
public record Scenario(String name, double tickFactor, double rebootRate, double silenceRate, double detachRate,
                       double simMoveRate, int noisyCustomerMultiplier, double faultFactor, boolean regionOutage) {

    Scenario(String name, double tickFactor, double rebootRate, double silenceRate, double detachRate, double simMoveRate,
             int noisyCustomerMultiplier, double faultFactor) {
        this(name, tickFactor, rebootRate, silenceRate, detachRate, simMoveRate, noisyCustomerMultiplier, faultFactor, false);
    }

    public static Scenario of(SimulatorProperties p) {
        return switch (p.scenario()) {
            case "peak" -> new Scenario("peak", 1 / 3.3, p.rebootRate(), p.silenceRate(), p.detachRate(), p.simMoveRate(),
                    p.noisyCustomerMultiplier(), 1);
            case "reboot-storm" -> new Scenario("reboot-storm", 1, 0.05, p.silenceRate(), p.detachRate(), p.simMoveRate(),
                    p.noisyCustomerMultiplier(), 1);
            case "noisy-customer" -> new Scenario("noisy-customer", 1, p.rebootRate(), p.silenceRate(), p.detachRate(),
                    p.simMoveRate(), 10, 1);
            case "silent-devices" -> new Scenario("silent-devices", 1, p.rebootRate(), 0.01, p.detachRate(), p.simMoveRate(),
                    p.noisyCustomerMultiplier(), 1);
            case "sim-moves" -> new Scenario("sim-moves", 1, p.rebootRate(), p.silenceRate(), p.detachRate(), 0.01,
                    p.noisyCustomerMultiplier(), 1);
            case "faults" -> new Scenario("faults", 1, p.rebootRate() * 10, p.silenceRate(), p.detachRate() * 10,
                    p.simMoveRate() * 10, p.noisyCustomerMultiplier(), 10);
            case "clean" -> new Scenario("clean", 1, 0, 0, 0, 0, 1, 0);
            // a whole region goes silent, then reconnects at once (FR-21); no per-line silences or
            // detaches, so every offline alert in the region would be a false one
            case "outage" -> new Scenario("outage", 1, p.rebootRate(), 0, 0, p.simMoveRate(), 1, 1, true);
            default -> new Scenario("steady", 1, p.rebootRate(), p.silenceRate(), p.detachRate(), p.simMoveRate(),
                    p.noisyCustomerMultiplier(), 1);
        };
    }
}
