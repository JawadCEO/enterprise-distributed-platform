# iot-telemetry-pipeline: Architecture Decisions

**Status:** draft for discussion.

## ADR-001: Streaming platform

| Option | For | Against |
|---|---|---|
| **Apache Kafka** | Ordered, replayable log; per-key ordering; very high throughput; mature tooling | Operational weight; partition count is hard to reduce later |
| Apache Pulsar | Built-in geo-replication and multi-tenancy; storage separate from brokers | More moving parts; smaller ecosystem |
| RabbitMQ | Simple; flexible routing | Lower throughput; replay and per-key ordering are weaker |
| Managed cloud stream | Nothing to operate | Vendor lock-in; cannot run on a laptop |

**Decision: Kafka.** The requirement set (per-line ordering, replay for dead letters, 50,000 events/sec) is exactly what a partitioned log provides.

**Evidence:** the earlier single-project prototype measured the producer settings this relies on: killing a partition leader lost 0 acknowledged records with `acks=all`, up to 4 with `acks=1`, and up to 23,446 with `acks=0`. Keying by device kept 0 out-of-order events; no key put 90.6% out of order. Those PoCs are carried into Phase 2.

## ADR-002: Delivery semantics

| Option | For | Against |
|---|---|---|
| **At-least-once with idempotent consumers** | Works across Kafka, PostgreSQL, and Redis; simple to reason about | Every write must be safe to repeat |
| Kafka exactly-once transactions | No duplicates inside Kafka | Does not cover writes to PostgreSQL or Redis; adds latency |
| At-most-once | Fastest | Loses events; violates RPO = 0 |

**Decision: at-least-once with idempotent consumers.** Kafka transactions stop at Kafka's edge, and this pipeline's results live in a database.

**Evidence:** in the prototype, exactly-once made the end-to-end median 5x slower (246 vs 50 ms), and neither mode lost or duplicated a stored row after a crash. Usage, where a duplicate costs money, gets its own guarantee in ADR-006.

## ADR-003: Ingestion protocol

| Option | For | Against |
|---|---|---|
| **HTTP first, protocol-neutral core** | Simple to build, secure, and load test; batching is easy; customer requests are HTTP anyway | Heavier per message than MQTT for constrained devices |
| MQTT first | The standard for real device fleets; persistent connections | Needs a broker and connection-state handling; harder to benchmark |
| Both from the start | Most realistic | Doubles the gateway work before the pipeline exists |

**Decision: HTTP first.** The gateway's core takes a protocol-neutral event, so an MQTT adapter can be added without touching the pipeline. To be confirmed by the HTTP vs MQTT PoC before Phase 0 closes.

## ADR-004: Durability and cross-region loss

| Option | For | Against |
|---|---|---|
| Synchronous replication across regions | Zero loss with no help from devices | Adds cross-region round trips to every write; breaks the latency target |
| **Synchronous within a region, asynchronous across, with device resend** | Meets the latency target; zero loss end to end | Devices must buffer unacknowledged events; duplicates after failover |
| Asynchronous everywhere | Fastest | Can lose acknowledged events |

**Decision: the second option.** Three copies within a region, with a write confirmed by at least two. Duplicates after failover are already handled by ADR-002.

## ADR-005: Ordering, reboots and late events

| Option | For | Against |
|---|---|---|
| Device timestamp | Simple | Device clocks are wrong; one bad clock reorders everything |
| Per-device sequence only | Simple; immune to clocks | A reboot restarts the sequence at 1, so every later event looks stale and the line freezes |
| **Boot counter + sequence for device events; network time for network events** | Survives reboots and bad clocks | Devices must persist a boot counter |
| Platform receive time | Always available | Retries and replays arrive late and would overwrite newer state |

**Decision:** partition by line ID so one line's events stay in order in Kafka. Device events compare `(bootId, sequence)`: a higher `bootId` wins, then a higher `sequence`. Network events compare network time per line. State changes only if the incoming event is newer; an older event can be recorded in history but never overwrites state.

## ADR-006: Duplicate events

| Option | For | Against |
|---|---|---|
| Database unique key only | No extra system | Daily-partitioned history can't enforce uniqueness across partitions; no help for state updates that aren't inserts |
| **Redis `SET NX` on `eventId` with a 48 h window, plus database keys where money is involved** | Cheap check before any write; usage protected by a durable key | Redis is one more dependency; a duplicate older than 48 h passes the Redis check |
| Kafka exactly-once | No duplicates inside Kafka | Doesn't stop resends from devices or the network; latency (ADR-002) |

**Decision:** Redis duplicate check for every event. Usage records are also stored keyed by `eventId` and only added to the total when that insert succeeds, so a usage record is counted exactly once even past the 48 h window or with Redis down. Events older than the Kafka retention are rejected at the gateway.

## ADR-007: Customer requests

| Option | For | Against |
|---|---|---|
| Synchronous call to the network inside the HTTP request | Simple | The network takes seconds to minutes; clients time out; no retries |
| **Accept (202), queue on Kafka, carry out asynchronously, confirm by event** | Fast answer; retries and pacing; survives restarts | A request is a state machine that needs a time limit and a sweeper |

**Decision:** the second option. The request row, the line's `PENDING` state and the Kafka command are written together through an outbox, so none can exist without the others. Commands are keyed by line, so two requests for one line never race. A sweeper fails any request past its time limit and restores the line's previous state.

## ADR-008: Webhook delivery

| Option | For | Against |
|---|---|---|
| Send inline from the state processor | Fewest parts | A slow customer endpoint stalls event processing for everyone |
| One shared retry queue | Simple | One customer's dead endpoint fills the queue and delays all others |
| **Dedicated notifier, per-customer delivery with back-off, parking after 24 h** | Isolation between customers; retries don't block processing | More moving parts; per-customer concurrency to manage |
| Customers consume a Kafka topic directly | No webhook retries on our side | Exposes Kafka to customers; most can't consume it |

**Decision:** a dedicated `notifier` reading `line.changes`, delivering at least once with a stable `notificationId`, signed with a per-customer secret, with per-customer concurrency limits and back-off. Undeliverable notifications are parked and stay readable by API for 30 days.
