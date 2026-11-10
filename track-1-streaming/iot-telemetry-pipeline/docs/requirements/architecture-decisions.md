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

**Decision:** partition by device ID so one device's events stay in order. Each event carries an ordering key of two parts: `bootId`, a counter the device increases on every restart, and `sequence`, which restarts at 0 on each boot. Keys are compared as `(bootId, sequence)`: a higher `bootId` always wins, and within one boot a higher `sequence` wins. The status update applies only if the incoming key is higher than the stored one, so a late or replayed event can never overwrite newer state.

A sequence number on its own is not enough. Devices often reset their counter when they reboot. With a plain sequence, every event after a reset would look older than the stored state, and the device's status would freeze permanently.

## ADR-006: Duplicate detection for telemetry rows

PostgreSQL requires a unique constraint on a partitioned table to include the partition column. Telemetry is partitioned by receive hour, so the database alone cannot reject a duplicate that a device resends in a later hour.

| Option | For | Against |
|---|---|---|
| **Redis key per `(deviceId, bootId, sequence)` with a TTL longer than the device resend window** | Cheap check before the insert; works across partitions | Adds a Redis dependency on the write path; if Redis is lost, a few duplicate rows can appear |
| Partition by device time | The unique constraint then works | Device clocks are untrusted; a bad clock lands rows in the wrong partition |
| Accept duplicate rows and remove them on read | Simplest write path | Every history query pays the cost; row counts are inflated |

**Decision: a Redis duplicate key, TTL 24 hours.** A unique constraint inside each partition still catches repeats within the same hour. Status is protected by ADR-005 either way, so a duplicate row after a Redis loss affects history only, never current status.
