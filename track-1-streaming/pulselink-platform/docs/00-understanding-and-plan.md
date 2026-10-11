# pulselink-platform: Understanding and Plan

**Status:** draft for discussion. No code yet.

## Brief

| Item | Value |
|---|---|
| Domain | Real-time patient monitoring: continuous vitals, anomaly detection, emergency alert routing to clinical staff |
| Stack | Java, Spring WebFlux, Apache Kafka, TimescaleDB / PostgreSQL, Redis, WebSocket / gRPC, Kubernetes |
| Alert latency target | p95 < 100ms, p99 < 300ms from triggering reading to alert dispatch |
| Volume target | 10,000 bed streams, 100,000 events/sec total |
| Availability target | 99.999% on the alert path |
| Privacy | HIPAA, mTLS in transit, encryption at rest, no PII in logs |

## My understanding

Bedside monitors stream vitals continuously. The platform must do two things with very different priorities. The first is to detect a dangerous reading and get an alert to the right clinician within a fraction of a second, and to keep doing so when parts of the system fail. The second is to store the full vitals history for charts and review, where a short delay is acceptable. The central design idea is to keep these two paths separate so that a slow database can never delay an alert.

## What this project demonstrates

- Separating a critical low-latency path from a bulk storage path
- Reactive, non-blocking stream handling
- Stateful per-patient anomaly detection over time windows
- Alert delivery with acknowledgement, escalation, and de-duplication
- Time-series storage with compression and roll-ups
- Privacy-aware logging and auditing

## Preliminary shape (for discussion)

**Services: 4, plus a simulator**

| Service | Responsibility |
|---|---|
| `vitals-gateway` | Accepts streaming vitals from monitors (gRPC or WebSocket), publishes to Kafka |
| `anomaly-detector` | Evaluates thresholds and windowed rules per patient, emits alert events |
| `alert-router` | Picks recipients, pushes alerts over WebSocket, tracks acknowledgement, escalates |
| `vitals-history` | Writes vitals to TimescaleDB and serves history queries |
| `bed-simulator` | Test tool that generates vitals streams, including scripted emergencies |

**Data stores**

- TimescaleDB: vitals hypertable, compression policy, continuous aggregates for charts
- PostgreSQL: patients, beds, thresholds, alerts, acknowledgements, escalation policy, access audit
- Redis: latest vitals per patient, alert suppression windows, clinician presence
- Kafka: vitals stream, alert events, retry and dead-letter topics

## Phase plan

| Phase | Focus for this project |
|---|---|
| 0 | Requirements, non-goals, alert-path SLO definition, ADR on streaming architecture |
| 1 | Hypertable design, chunk and compression policy, alert lifecycle schema, key queries |
| 2 | Topic layout, partition by patient, priority handling for alerts, retries and dead letters |
| 3 | Two-path design, detector state model, alert state machine, escalation, back-pressure |
| 4 | mTLS, role-based access, audit trail of who saw what, log scrubbing |
| 5 | Alert-latency measurement end to end, SLO burn alerts, cost model |
| 6 | Latency benchmark, failure drills on the alert path, missed-alert and duplicate-alert tests |
| 7 | Deployment, autoscaling, zero-downtime release of the alert path |
| 8 | ADRs, runbook, results write-up |

## Laptop fit (16GB)

Roughly 6GB: Kafka, TimescaleDB, Redis, four JVM services, simulator. Fits, with heaps capped.

## Risks and open questions

1. **Does the alert path go through Kafka at all?** Kafka adds a hop and a few milliseconds but gives durability and replay. A direct in-memory path is faster but can lose an alert on a crash. This is the most important decision in the project and belongs in Phase 0.
2. **Five nines cannot be demonstrated.** 99.999% allows about 5 minutes of downtime per year. It is a design target shown through redundancy and failure drills, not a measured result.
3. **HIPAA wording.** Compliance is an organisational property that a portfolio project cannot claim. Recommendation: describe it as a HIPAA-aligned design and use synthetic patient data only.
4. **Detection scope.** Fixed thresholds and simple windowed rules (for example, heart rate above a limit for 10 seconds), or a statistical or ML model? Recommendation: rules first, which are explainable and testable.
5. **100,000 events/sec is a design target.** That is 10 readings per second per bed. The laptop will run a fraction of the beds; results are reported as measured plus projected.
6. **Monitor protocol.** gRPC streaming, WebSocket, or both for inbound vitals?
