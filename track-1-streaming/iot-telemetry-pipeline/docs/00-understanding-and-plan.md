# iot-telemetry-pipeline: Understanding and Plan

**Status:** draft for discussion. No code yet.

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

**Services: 4, plus a simulator**

| Service | Responsibility |
|---|---|
| `ingestion-gateway` | Accepts device events, authenticates, rate limits (Redis), publishes to Kafka |
| `telemetry-processor` | Validates, de-duplicates, enriches, writes telemetry and latest status |
| `device-status-api` | Read API for current status and recent history |
| `dlq-handler` | Classifies failed events, retries with backoff, supports manual replay |
| `device-simulator` | Test tool that generates device traffic, including bad and duplicate events |

**Data stores**

- PostgreSQL: device registry, time-partitioned telemetry, latest status per device, dead-letter records
- Redis: rate-limit counters, de-duplication keys, latest-status cache
- Kafka: raw events, validated events, tiered retry topics, dead-letter topic

## Phase plan

| Phase | Focus for this project |
|---|---|
| 0 | Requirements, non-goals, ADR on streaming platform choice, delivery semantics |
| 1 | Access patterns, telemetry partitioning scheme, latest-status upsert, key queries |
| 2 | Topic layout, partition key and count, retry tiers, dead-letter and replay design |
| 3 | Service boundaries, consumer concurrency, idempotency, rate limiter, circuit breakers |
| 4 | Device authentication, service-to-service trust, audit logging |
| 5 | Traces across the Kafka hop, consumer lag and golden-signal dashboards, cost model |
| 6 | Integration tests, broker-kill and database-kill drills, load benchmark |
| 7 | Kubernetes manifests, lag-based autoscaling, rollout and rollback |
| 8 | ADRs, runbook, results write-up |

## Laptop fit (16GB)

Roughly 5GB: one Kafka broker in KRaft mode (three for failure drills), PostgreSQL, Redis, four JVM services with capped heaps, and the simulator. Comfortable.

## Risks and open questions

1. **Ingestion protocol.** HTTP, MQTT, or both? Real IoT fleets mostly use MQTT. HTTP is simpler to build and load test. Recommendation: HTTP first, with the gateway designed so an MQTT adapter can be added.
2. **RPO = 0 across regions conflicts with the latency target.** Zero loss across regions needs synchronous cross-region replication, which adds tens of milliseconds per write. We must pick: zero loss within a region and near-zero across regions, or relax the latency target.
3. **Multi-region active-active cannot be run on a laptop.** Recommendation: design it fully in the documents and, at most, simulate two regions with two local clusters and replication.
4. **Legacy side.** Do we model the legacy poller (for a migration and cut-over story), or only the new pipeline?
5. **50,000 events/sec is a design target.** The laptop figure will be lower and reported as measured, with a projection.
