# pulselink-platform: Non-Functional Requirements

**Status:** draft for discussion. Items marked **[assumption]** are working assumptions and need confirmation.

## 1. Two paths, two standards

The system has two paths with deliberately different targets. Holding the history path to the alert path's standard would cost a great deal for no clinical benefit. Holding the alert path to the history path's standard would put patients at risk.

| | Alert path | History path |
|---|---|---|
| Purpose | Detect, deliver, acknowledge, escalate | Store and serve vitals history |
| Components | gateway, Kafka, anomaly-detector, alert-router, Redis, dashboard WebSocket | gateway, Kafka, vitals-history, TimescaleDB |
| Latency | Sub-second (p95 < 100 ms) | Seconds are acceptable |
| Availability | 99.999% design target | 99.9% **[assumption]** |
| Data loss tolerated | None for raised alerts | A few seconds of readings on a node failure **[assumption]** |
| When overloaded | Protected | Shed or delayed first |

**Rule:** no component on the alert path may make a synchronous call to the history path or wait on a TimescaleDB or PostgreSQL write.

## 2. Service level indicators and objectives

Each target is defined by an indicator (what is measured, and where) and an objective (the threshold).

| ID | Indicator | Measured from → to | Objective |
|---|---|---|---|
| NFR-01 | Alert latency | Gateway receive timestamp of the triggering reading → alert-router completes the WebSocket write to the clinician's connection | p95 < 100 ms, p99 < 300 ms |
| NFR-02 | Client-observed alert latency | Gateway receive timestamp → dashboard reports receipt | Reported, no objective (includes the client network, which the system does not control) |
| NFR-03 | Ingestion throughput | Readings accepted per second, sustained for 30 minutes | 100,000 readings/s at design scale (20,000 frames/s) with consumer lag not growing |
| NFR-04 | History freshness | Gateway receive → reading queryable in TimescaleDB | p95 < 5 s **[assumption]** |
| NFR-05 | Live view freshness | Gateway receive → unit dashboard shows the value | p95 < 1 s **[assumption]** |
| NFR-06 | History query latency | Query received → response sent, one patient, 24 h at one-minute resolution | p95 < 500 ms **[assumption]** |
| NFR-07 | Alert-path availability | Share of one-minute intervals in which the canary bed's alert is delivered within 300 ms | 99.999% design target (see §5) |
| NFR-08 | History-path availability | Share of one-minute intervals in which history writes and queries succeed | 99.9% **[assumption]** |
| NFR-09 | Escalation accuracy | Unacknowledged alerts escalated within timeout + 1 s, divided by all unacknowledged alerts | 100% |

### Latency budget for NFR-01 (p95)

The 100 ms target is split across the hops so that each service has a number it can be tested against. These are budgets, not measurements; Phase 8 replaces them with measured values.

| Hop | Budget |
|---|---|
| Gateway: decode, validate, enrich with patient ID | 5 ms |
| Publish to vitals stream (`acks=all`, `linger.ms=0`) | 10 ms |
| Detector: consume | 10 ms |
| Detector: evaluate rules, check suppression | 5 ms |
| Publish to alert stream (`acks=all`) | 10 ms |
| Router: consume, resolve recipients | 10 ms |
| Router: WebSocket write | 5 ms |
| **Total budgeted** | **55 ms** |
| Headroom for GC pauses, rebalances, and queueing | 45 ms |

## 3. Durability and correctness

| ID | Requirement | Target |
|---|---|---|
| NFR-10 | Raised-alert durability | 0 raised alerts lost. An alert is "raised" once it is written to the replicated alert stream (`acks=all`, `min.insync.replicas=2`, replication factor 3). |
| NFR-11 | Missed detections | 0 missed expected alerts across all scripted simulator scenarios, including during failure drills |
| NFR-12 | Duplicate alerts | Duplicates are possible because delivery is at-least-once. The dashboard removes duplicates by alert ID, so a clinician sees each alert once. Duplicate raises after failover are counted and reported. |
| NFR-13 | Ordering | Readings for one patient are processed in the order received. Ordering across patients is not guaranteed and not needed. |
| NFR-14 | Late readings | Window rules use device time. Readings more than 5 s late **[assumption]** are stored but do not re-open a closed window; the late count is measured. |
| NFR-15 | History durability | At most a few seconds of readings lost on a node failure **[assumption]**; measured in drills |

## 4. Failure behaviour and recovery

| ID | Failure | Expected behaviour | Target |
|---|---|---|---|
| NFR-16 | One anomaly-detector instance | Its partitions move to the remaining instances, which rebuild window state from recent readings | Alerting resumes < 5 s **[assumption]**; no missed alert |
| NFR-17 | One alert-router instance | Another instance takes over its alerts; open alerts and escalation timers are rebuilt from the alert lifecycle stream | Resumes < 5 s **[assumption]**; no escalation skipped |
| NFR-18 | One Kafka broker | Partition leadership moves to a replica | No raised alert lost |
| NFR-19 | TimescaleDB down | Alerts continue unaffected; readings wait in Kafka and are written after recovery | 0 effect on NFR-01; history catches up after recovery |
| NFR-20 | PostgreSQL down | Alerts continue using cached thresholds, assignments, and policies; lifecycle records wait in Kafka and are written after recovery; configuration changes are refused | 0 effect on NFR-01 |
| NFR-21 | Redis down | Suppression and presence fall back to per-instance memory | Brief duplicate alerts acceptable; no missed alert |
| NFR-22 | Clinician connection drops | Open alerts re-sent on reconnect; escalation timers keep running | No escalation delayed by the disconnect |
| NFR-23 | Overload (input above capacity) | History writes slow down first; the alert path keeps its consumers and capacity; monitors are slowed by back-pressure | NFR-01 holds up to 1.5× design load **[assumption]** |

Recovery objectives for stored data, per data class:

| Data | RPO (max data loss) | RTO (max time to restore) |
|---|---|---|
| Raised alerts and lifecycle events | 0 | 5 s for alerting **[assumption]** |
| Configuration, assignments, audit | 0 (synchronous replica) **[assumption]** | 15 min **[assumption]** |
| Vitals history | A few seconds **[assumption]** | 1 h **[assumption]** |

## 5. About the five-nines target

99.999% allows about 5 minutes and 15 seconds of downtime a year. It cannot be measured in a portfolio project, and it is not claimed as a result. It is a **design target** **[assumption]**, shown by:

1. No single point of failure on the alert path: at least two instances of each service, and a Kafka replication factor of 3.
2. Failure drills (Phase 8) that kill each component under load and measure how long alerting is out each time.
3. Continuous measurement through the canary bed (NFR-07).

The results report gives the measured downtime per drill and the arithmetic showing what yearly availability those numbers would imply at a stated failure rate, clearly labelled as a projection.

## 6. Scalability

| ID | Requirement |
|---|---|
| NFR-24 | Every service scales horizontally. Throughput grows by adding instances up to the partition count of its input stream. |
| NFR-25 | Streams are partitioned by patient, so all state for one patient lives in one detector instance and no cross-instance coordination is needed per reading. |
| NFR-26 | Partition counts are chosen for design scale (10,000 beds) up front, because changing them later moves patients between partitions. |
| NFR-27 | Laptop runs measure per-instance throughput at a few hundred beds; 10,000-bed figures are projected from those measurements and labelled as projections. |

## 7. Security and privacy

The design is HIPAA-aligned. Compliance itself is an organisational certification that a portfolio project cannot claim.

| ID | Requirement | Target |
|---|---|---|
| NFR-28 | In transit | mTLS between all services; TLS from monitors and dashboards |
| NFR-29 | At rest | Encrypted databases, Kafka volumes, and backups |
| NFR-30 | Identity separation | Patient name, record number, and date of birth live only in the patient identity table. Every other table, stream, and message uses the internal patient ID. |
| NFR-31 | Logs and traces | No patient names, record numbers, or dates of birth. Internal IDs only. Enforced by a log scrubber and a CI test that scans log output from the scenario runs. |
| NFR-32 | Access | Role-based, scoped to unit; least privilege per service account |
| NFR-33 | Audit | Append-only. Every read of patient data and every alert action recorded with who, what, when, where. Audit writes must not block the alert path. |

## 8. Observability

| ID | Requirement |
|---|---|
| NFR-34 | Every reading and alert carries a trace context, so one alert can be followed from gateway to dashboard. |
| NFR-35 | Metrics for every SLI above, plus consumer lag per stream, detector state size, open alerts by severity, and escalations by tier. |
| NFR-36 | SLO burn-rate alerts for alert latency and canary success, so the operator learns about a problem before the error budget is spent. |

## 9. Resource and operational constraints

| ID | Requirement |
|---|---|
| NFR-37 | The full stack runs on a 16 GB laptop in about 6 GB: Kafka, TimescaleDB, PostgreSQL, Redis, four JVM services with capped heaps, and the simulator. |
| NFR-38 | The alert path can be released with zero downtime: rolling deploys must not drop open alerts or reset escalation timers. |
| NFR-39 | All behaviour above is covered by automated tests: scenario tests for detection, missed-alert and duplicate-alert tests, escalation timing tests, and scripted failure drills. |
