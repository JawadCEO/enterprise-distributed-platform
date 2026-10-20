# pulselink-platform: Open Questions

Decisions needed before the data layer design. Until each is confirmed, the requirement documents use the **working assumption** shown, marked **[assumption]** where it appears.

| # | Question | Working assumption | Status |
|---|---|---|---|
| 1 | **Does the alert path go through Kafka?** Kafka adds a hop and a few milliseconds but makes alerts durable and replayable. A direct in-memory path is faster but can lose an alert on a crash. This is the most important decision in the project. | Yes, on both hops: gateway → vitals stream → detector → alert stream → router. `acks=all`, `min.insync.replicas=2`, `linger.ms=0`. Alerts use their own stream so a vitals backlog never delays them. Consumer session timeout and cooperative rebalancing are tuned to meet the 5 s failover target. | Open |
| 2 | **Detection scope.** Fixed thresholds and window rules only, or also a statistical or ML model? | Rules only: threshold, window, rate-of-change, and signal rules behind one rule interface. ML is a non-goal. | Open |
| 3 | **Monitor protocol.** gRPC streaming, WebSocket, or both for incoming vitals? | gRPC bidirectional streaming from monitors, using its flow control for back-pressure. WebSocket for clinician dashboards. | Open |
| 4 | **Retention.** Are 30 days of raw readings and 1 year of one-minute roll-ups acceptable? | Yes, plus one-hour roll-ups kept 7 years. About 2.6 TB of compressed raw data at design scale, a projection only. | Open |
| 5 | **Escalation timeouts.** Is 30 seconds for critical alerts a reasonable starting policy? | Critical: assigned nurse, then charge nurse at 30 s, then unit physician at 60 s. High: 2 min. Medium: 5 min. Advisory: no escalation. | Open |
| 6 | **Five nines.** Is 99.999% a design target, shown through redundancy and failure drills, not a measured figure? | Yes. Results report measured alerting downtime per drill, and any yearly figure is labelled as a projection. | Open |

## Smaller assumptions introduced by the requirements

These came up while writing the requirements. They are reasonable starting values and can change without affecting the design.

| Assumption | Where |
|---|---|
| Monitors send 2 frames per second, about 5 readings each | data.md §8 |
| A monitor silent for 10 s raises a "signal lost" alert | FR-12 |
| Suppression window of 120 s per patient and rule | FR-15 |
| Readings more than 5 s late do not re-open a closed window | NFR-14 |
| History path availability 99.9%; history freshness p95 < 5 s | NFR-04, NFR-08 |
| Alert path failover < 5 s | NFR-16, NFR-17 |
| Vitals stream kept 24 h for replay | data.md §9 |
