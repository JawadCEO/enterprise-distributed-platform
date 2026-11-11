# iot-telemetry-pipeline

**Status:** building. Parts 2a (lines and network events) and 2b (customer requests and provisioning) are built and run end to end; see the [2a run](results/carrier-2a-run-2026-11-10.md) and the [2b run](results/carrier-2b-run-2026-11-11.md). Webhooks and the outage guard come next.

A mobile carrier's IoT connectivity platform (like Verizon ThingSpace): millions of SIM-connected trackers, meters and sensors for business customers. Start with [how it works](requirements/how-it-works.md).

## Documents

| Document |
|---|
| [How it works: actors, flows, SIM types, what we do with the data](requirements/how-it-works.md) |
| [What the real-time data looks like](requirements/real-time-data.md) |
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
| Domain | Telecom: a carrier IoT platform that tracks 5M SIM lines (removable, soldered eSIM, integrated iSIM), carries out customer line requests, and notifies customers by webhook instead of making them poll |
| Stack | Java, Spring Boot, Apache Kafka, PostgreSQL, Redis, Kubernetes, Docker, OpenTelemetry |
| Throughput target | 50,000 events/sec at peak |
| Latency target | p95 < 50ms, p99 < 150ms end to end |
| Availability target | 99.99%, multi-region active-active |
| Recovery targets | RPO = 0, RTO < 60s |

## My understanding

Today customers poll the carrier to learn whether their devices are online, whether their requests went through, and how much data they've used. The new platform inverts it: the network and devices push events, customers send requests, and the platform keeps every line's state current and tells customers by webhook when something changes. Bad events go to retry and dead-letter paths instead of blocking the stream, and rate limits stop one customer from slowing the others.

## What this project demonstrates

- Kafka partitioning and per-line ordering, including across device reboots
- At-least-once delivery made safe by idempotent consumers, and usage counted exactly once
- Long-running requests as state machines with an outbox and a sweeper
- Webhook delivery with per-customer isolation
- Non-blocking retries, dead letters and replay
- Distributed rate limiting and back-pressure

## Services

**Six services plus a simulator** (see the [high-level design](architecture/high-level-design.md#2-services))

| Service | Responsibility | Built |
|---|---|---|
| `event-ingest` | Accepts network and device events, authenticates, rate limits, publishes to Kafka | Yes |
| `customer-api` | Registry and line import, customer line requests, line and request queries, dead letters and replay; later webhook subscriptions | All but webhooks |
| `state-processor` | Applies events to line, device and usage state, in order, without duplicates | Yes |
| `presence-monitor` | Marks lines offline when keep-alives stop; outage guard | Offline marking |
| `provisioning-worker` | Carries out line changes against the network, with retries, pacing and a sweeper | Yes; bulk jobs later |
| `notifier` | Delivers webhooks with retries | Part 2c |
| `network-simulator` | Test tool: network, devices and the network's provisioning interface, with faults on demand; reconciles usage and request outcomes at the end | Yes |

**Data stores**

- PostgreSQL: registry, current line state, 90-day history, usage, requests, notifications, dead letters
- Redis: rate-limit counters, duplicate keys, state cache, last-seen sorted set
- Kafka: network events, retry tiers, dead letters, line commands, line changes

## Phase plan

| Phase | Focus for this project |
|---|---|
| 0 | Domain and how it works, requirements, ADRs 001 to 008, high-level design, and two PoCs (polling vs push, HTTP vs MQTT). Drafted; closes when the open questions are answered and the PoCs are run |
| 1 | Data layer: access patterns, PostgreSQL schema (registry, line and device state guarded by boot counter and sequence, daily-partitioned history and drop job, usage keyed by event, requests, notifications), Redis key design, key queries |
| 2 | Kafka: topic layout (`network.events`, `line.commands`, `line.changes`), partition key and count for 3x peak, message format and schema registry, retry tiers, dead-letter and replay, cross-region topic replication design |
| 3 | Services: API contracts (events, requests, webhooks), web stack and Java version, processor batch and commit strategy, request state machine and sweeper, notifier delivery, rate-limit algorithm, presence-monitor scan and outage guard, and the `network-simulator` with its fault scenarios |
| 4 | Security: device, network and customer authentication, customer isolation on every query, webhook signing, service-to-service trust and TLS, audit log for registry changes and replays |
| 5 | Observability: one trace from gateway across Kafka to the database, freshness latency per hop against the 50ms budget, consumer lag, offline-detection lag, dead-letter rate, full-scale cost projection |
| 6 | Testing and drills: integration tests, broker, database, Redis, and instance kills with reconciliation of every acknowledged event, laptop load benchmark with projection, two-region simulation (run on its own for memory) |
| 7 | Kubernetes: manifests, lag-based autoscaling for the processor, rolling deploy and one-step rollback |
| 8 | Write-up: final ADRs, runbook, results, multi-region design, and the documented cut-over from the legacy poller |

## Laptop fit (16GB)

Roughly 7GB: one Kafka broker in KRaft mode (three for failure drills), PostgreSQL, Redis, six JVM services with capped heaps, and the simulator. Comfortable.

## Open questions

See [open-questions.md](requirements/open-questions.md). The domain and end-to-end picture are agreed; the remaining questions confirm scope and sizing.
