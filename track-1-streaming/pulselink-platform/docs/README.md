# pulselink-platform

**Status:** draft for discussion. No code yet.

## Documents

| Document |
|---|
| [Problem statement, goals, users, non-goals](requirements/problem-statement.md) |
| [Functional requirements](requirements/functional-requirements.md) |
| [Non-functional requirements and disaster recovery](requirements/non-functional-requirements.md) |
| [Data, end-to-end flow, volumes, retention, sensitivity](requirements/data.md) |
| [Decisions still needed, with working assumptions](requirements/open-questions.md) |

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

Each phase starts with a design discussion. Code is written only after that phase's design is agreed.

| Phase | Focus for this project |
|---|---|
| 0 | Requirements (drafted), then ADRs for the decisions everything rests on: alerts go through Kafka, alert and history paths stay separate, detection is rules only |
| 1 | Data model: vitals hypertable, chunk, compression and retention policy, one-minute and one-hour continuous aggregates, PostgreSQL schema (separate patient identity table, admissions, versioned thresholds, alerts and alert events, audit), key queries |
| 2 | Streaming layout: topics, partitioning by patient and partition count, a separate alert stream, compacted config and admission topics, dead letters, replication, consumer tuning for 5-second failover, broker count on the laptop |
| 3 | Simulator, ingestion and history path: bed-simulator with scripted scenarios and their expected alerts, vitals-gateway (gRPC, validation, enrichment, back-pressure), vitals-history writer and query API, live unit view |
| 4 | Detection: per-patient state model, rule interface, threshold, window, rate-of-change and signal-loss rules, suppression and re-raise, late readings, rebuilding state after a rebalance |
| 5 | Alert delivery: alert-router, alert lifecycle state machine, routing to assigned clinicians, WebSocket push, acknowledgement, escalation timers, re-send on reconnect, rebuilding timers after failover, a minimal clinician dashboard |
| 6 | Security and privacy: mTLS, role-based access by unit, audit of every patient-data read and alert action, log scrubbing with a CI check |
| 7 | Observability: tracing from gateway to dashboard, metrics for every SLI, canary bed, SLO burn-rate alerts |
| 8 | Benchmark and failure drills: alert latency at laptop scale, projection to 10,000 beds, killing each component under load, missed-alert and duplicate-alert tests |
| 9 | Kubernetes deployment, autoscaling, zero-downtime release of the alert path |
| 10 | ADR review, runbook, results write-up, cost projection at design scale |

## Laptop fit (16GB)

Roughly 6GB: Kafka, TimescaleDB, Redis, four JVM services, simulator. Fits, with heaps capped.

## Risks and open questions

1. **Does the alert path go through Kafka at all?** Kafka adds a hop and a few milliseconds but gives durability and replay. A direct in-memory path is faster but can lose an alert on a crash. This is the most important decision in the project and belongs in Phase 0.
2. **Five nines cannot be demonstrated.** 99.999% allows about 5 minutes of downtime per year. It is a design target shown through redundancy and failure drills, not a measured result.
3. **HIPAA wording.** Compliance is an organisational property that a portfolio project cannot claim. Recommendation: describe it as a HIPAA-aligned design and use synthetic patient data only.
4. **Detection scope.** Fixed thresholds and simple windowed rules (for example, heart rate above a limit for 10 seconds), or a statistical or ML model? Recommendation: rules first, which are explainable and testable.
5. **100,000 events/sec is a design target.** That is 10 readings per second per bed. The laptop will run a fraction of the beds; results are reported as measured plus projected.
6. **Monitor protocol.** gRPC streaming, WebSocket, or both for inbound vitals?
