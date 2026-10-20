# iot-telemetry-pipeline: Architecture Decisions

**Status:** draft for discussion.

## ADR-001: Streaming platform

| Option | For | Against |
|---|---|---|
| **Apache Kafka** | Ordered, replayable log; per-key ordering; very high throughput; mature tooling | Operational weight; partition count is hard to reduce later |
| Apache Pulsar | Built-in geo-replication and multi-tenancy; storage separate from brokers | More moving parts; smaller ecosystem |
| RabbitMQ | Simple; flexible routing | Lower throughput; replay and per-key ordering are weaker |
| Managed cloud stream | Nothing to operate | Vendor lock-in; cannot run on a laptop |

**Decision: Kafka.** The requirement set (per-device ordering, replay for dead letters, 50,000 events/sec) is exactly what a partitioned log provides.

## ADR-002: Delivery semantics

| Option | For | Against |
|---|---|---|
| **At-least-once with idempotent consumers** | Works across Kafka, PostgreSQL, and Redis; simple to reason about | Every write must be safe to repeat |
| Kafka exactly-once transactions | No duplicates inside Kafka | Does not cover writes to PostgreSQL or Redis; adds latency |
| At-most-once | Fastest | Loses events; violates RPO = 0 |

**Decision: at-least-once with idempotent consumers.** Kafka transactions stop at Kafka's edge, and this pipeline's results live in a database.

## ADR-003: Ingestion protocol

| Option | For | Against |
|---|---|---|
| **HTTP first, protocol-neutral core** | Simple to build, secure, and load test; batching is easy | Heavier per message than MQTT for constrained devices |
| MQTT first | The standard for real device fleets; persistent connections | Needs a broker and connection-state handling; harder to benchmark |
| Both from the start | Most realistic | Doubles the gateway work before the pipeline exists |

**Decision: HTTP first.** The gateway's core takes a protocol-neutral event, so an MQTT adapter can be added without touching the pipeline.

## ADR-004: Durability and cross-region loss

| Option | For | Against |
|---|---|---|
| Synchronous replication across regions | Zero loss with no help from devices | Adds cross-region round trips to every write; breaks the latency target |
| **Synchronous within a region, asynchronous across, with device resend** | Meets the latency target; zero loss end to end | Devices must buffer unacknowledged events; duplicates after failover |
| Asynchronous everywhere | Fastest | Can lose acknowledged events |

**Decision: the second option.** Three copies within a region, with a write confirmed by at least two. Duplicates after failover are already handled by ADR-002.

## ADR-005: Ordering and late events

**Decision:** partition by device ID so one device's events stay in order. Each event carries a per-device sequence number. The status update applies only if the incoming sequence is higher than the stored one, so a late or replayed event can never overwrite newer state.
