# iot-telemetry-pipeline

**Status:** draft for discussion. No code yet.

## Documents

| Document |
|---|
| [Problem statement, goals, users, non-goals](requirements/problem-statement.md) |
| [Functional requirements](requirements/functional-requirements.md) |
| [Non-functional requirements and disaster recovery](requirements/non-functional-requirements.md) |
| [Data and flow: what we receive, process, store, and return](requirements/data.md) |
| [Architecture decisions](requirements/architecture-decisions.md) |
| [Decisions still needed](requirements/open-questions.md) |
| [High-level design](architecture/high-level-design.md) |

## Brief

| Item | Value |
|---|---|
| Domain | Ingestion and status processing for millions of IoT devices, replacing legacy polling with an event-driven design |
| Stack | Java, Spring Boot, Apache Kafka, PostgreSQL, Redis, Kubernetes, Docker, OpenTelemetry |
| Throughput target | 50,000 events/sec at peak |
| Latency target | p95 < 50ms, p99 < 150ms end to end |
| Availability target | 99.99%, multi-region active-active |
| Recovery targets | RPO = 0, RTO < 60s |

## My understanding

The legacy system asks each device for its status on a schedule. That wastes capacity on devices with nothing new and reports changes late. The new system inverts it: devices push events, a gateway accepts them and writes them to Kafka, and downstream consumers validate, de-duplicate, and store them. Bad events go to a dead-letter path instead of blocking the stream, and rate limiting stops one noisy device or tenant from starving the others.

## What this project demonstrates

- Kafka partitioning and ordering per device
- At-least-once delivery made safe by idempotent consumers
- Non-blocking retries and dead-letter handling with replay
- Distributed rate limiting
- Back-pressure and behaviour under broker or database failure

## Preliminary shape (for discussion)

**Services: 5, plus a simulator** (see the [high-level design](architecture/high-level-design.md#2-services))

| Service | Responsibility |
|---|---|
| `ingestion-gateway` | Accepts device events, authenticates, rate limits (Redis), publishes to Kafka |
| `telemetry-processor` | Validates, de-duplicates, writes telemetry and latest status, runs the retry tiers |
| `presence-monitor` | Marks devices offline when their heartbeat window passes |
| `dead-letter-service` | Stores events that ran out of retries; supports inspection and replay |
| `device-status-api` | Device registry; current status and history queries |
| `device-simulator` | Test tool that generates device traffic, including bad and duplicate events |

**Data stores**

- PostgreSQL: device registry, time-partitioned telemetry, latest status per device, dead-letter records
- Redis: rate-limit counters, de-duplication keys, latest-status cache
- Kafka: raw events, validated events, tiered retry topics, dead-letter topic

## Phase plan

| Phase | Focus for this project |
|---|---|
| 0 | Requirements, ADRs 001 to 005, high-level design. Drafted; closes when the open questions are answered |
| 1 | Data layer: access patterns, PostgreSQL schema (hourly telemetry partitions and drop job, status upsert guarded by sequence, status changes), Redis key design (status cache, last-seen sorted set, rate-limit counters), key queries, and whether a Redis duplicate check is needed |
| 2 | Kafka: topic layout including `device.status-changed`, partition key and count for 3x peak, message format and schema registry, retry tiers, dead-letter and replay, cross-region topic replication design |
| 3 | Services: API contracts for the three public interfaces, web stack and Java version, processor batch and commit strategy, consumer concurrency, rate-limit algorithm, presence-monitor scan, circuit breakers, and the `device-simulator` with its fault scenarios |
| 4 | Security: device authentication, tenant isolation on every query, service-to-service trust and TLS, audit log for registry changes and replays |
| 5 | Observability: one trace from gateway across Kafka to the database, freshness latency per hop against the 50ms budget, consumer lag, offline-detection lag, dead-letter rate, full-scale cost projection |
| 6 | Testing and drills: integration tests, broker, database, Redis, and instance kills with reconciliation of every acknowledged event, laptop load benchmark with projection, two-region simulation (run on its own for memory) |
| 7 | Kubernetes: manifests, lag-based autoscaling for the processor, rolling deploy and one-step rollback |
| 8 | Write-up: final ADRs, runbook, results, multi-region design, and the documented cut-over from the legacy poller |

## Laptop fit (16GB)

Roughly 5GB: one Kafka broker in KRaft mode (three for failure drills), PostgreSQL, Redis, four JVM services with capped heaps, and the simulator. Comfortable.

## Risks and open questions

1. **Ingestion protocol.** HTTP, MQTT, or both? Real IoT fleets mostly use MQTT. HTTP is simpler to build and load test. Recommendation: HTTP first, with the gateway designed so an MQTT adapter can be added.
2. **RPO = 0 across regions conflicts with the latency target.** Zero loss across regions needs synchronous cross-region replication, which adds tens of milliseconds per write. We must pick: zero loss within a region and near-zero across regions, or relax the latency target.
3. **Multi-region active-active cannot be run on a laptop.** Recommendation: design it fully in the documents and, at most, simulate two regions with two local clusters and replication.
4. **Legacy side.** Do we model the legacy poller (for a migration and cut-over story), or only the new pipeline?
5. **50,000 events/sec is a design target.** The laptop figure will be lower and reported as measured, with a projection.
