package com.edp.iot.simulator;

/**
 * Named, repeatable traffic presets. Failure drills (broker kill, gateway outage) use {@code steady}
 * traffic while the operator breaks something; the scenario only shapes the traffic.
 */
public record Scenario(String name, double heartbeatFactor, double rebootRate, double silenceRate,
                       int noisyTenantMultiplier, double faultFactor) {

    public static Scenario of(SimulatorProperties p) {
        return switch (p.scenario()) {
            case "peak" -> new Scenario("peak", 1 / 3.3, p.rebootRate(), p.silenceRate(), p.noisyTenantMultiplier(), 1);
            case "reboot-storm" -> new Scenario("reboot-storm", 1, 0.05, p.silenceRate(), p.noisyTenantMultiplier(), 1);
            case "noisy-tenant" -> new Scenario("noisy-tenant", 1, p.rebootRate(), p.silenceRate(), 10, 1);
            case "silent-devices" -> new Scenario("silent-devices", 1, p.rebootRate(), 0.01, p.noisyTenantMultiplier(), 1);
            case "faults" -> new Scenario("faults", 1, p.rebootRate() * 10, p.silenceRate(), p.noisyTenantMultiplier(), 10);
            case "clean" -> new Scenario("clean", 1, 0, 0, 1, 0);
            default -> new Scenario("steady", 1, p.rebootRate(), p.silenceRate(), p.noisyTenantMultiplier(), 1);
        };
    }
}
