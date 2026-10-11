# iot-telemetry-pipeline: Phase 0, Problem Definition and Requirements

**Status:** draft for discussion. Items marked **[assumption]** are my working guesses and need confirmation.

## 1. Problem statement

The legacy platform learns device state by polling: a scheduler asks each device for its status on a fixed interval. With millions of devices this has three costs.

- **Stale data.** A change is seen only at the next poll, so detection delay equals the polling interval.
- **Wasted capacity.** Most polls return "nothing changed", yet each one costs a connection and a query.
- **Poor failure behaviour.** A slow or unreachable set of devices holds up the polling cycle for the rest.

The new platform inverts the flow. Devices push events when something happens. The platform accepts them quickly, stores them durably, processes them in order per device, and isolates failures so that one bad event or one noisy device cannot stall the others.

## 2. Goals

1. Accept 50,000 events/sec at peak without losing an acknowledged event.
2. Make a device's new status queryable within 50ms (p95) of acceptance.
3. Keep accepting and processing events through the loss of any single node.
4. Keep bad events and abusive senders from affecting healthy traffic.

## 3. Functional requirements

| ID | Requirement |
|---|---|
| FR-1 | Accept telemetry and status events from authenticated devices, singly or in batches |
| FR-2 | Acknowledge an event only after it is durably stored in the log |
| FR-3 | Validate each event against a schema; reject malformed events with a reason |
| FR-4 | Process events for one device in the order the device produced them |
| FR-5 | Detect and discard duplicate events |
| FR-6 | Maintain the latest status of every device |
| FR-7 | Store telemetry history for a defined retention period |
| FR-8 | Serve read queries: current status of a device, recent history of a device, devices by status |
| FR-9 | Rate limit per device and per tenant, returning a clear "slow down" response |
| FR-10 | Retry events that fail for temporary reasons, without blocking the events behind them |
| FR-11 | Move events that cannot be processed to a dead-letter store with the failure reason |
| FR-12 | Allow dead-lettered events to be inspected and replayed |
| FR-13 | Mark a device offline when no event has arrived within its expected heartbeat window **[assumption]** |
| FR-14 | Register, update, and deactivate devices |

## 4. Non-functional requirements

| Area | Target | How it is defined |
|---|---|---|
| Throughput | 50,000 events/sec peak | Sustained for at least 10 minutes with no growth in consumer lag |
| Latency | p95 < 50ms, p99 < 150ms | From the gateway receiving an event to its status being queryable |
| Availability | 99.99% | Ingestion and status reads; allows about 52 minutes of downtime a year |
| Durability | RPO = 0 | No acknowledged event is lost |
| Recovery | RTO < 60s | Time to resume full processing after loss of a node or a region |
| Ordering | Per device | No ordering guarantee across devices |
| Delivery | At-least-once, with idempotent processing | The stored result is the same as if each event were processed exactly once |
| Scalability | Horizontal | Adding instances raises throughput without redesign |

## 5. Non-goals

- Sending commands or configuration to devices
- Firmware updates and device provisioning hardware flows
- Analytics, dashboards, and long-term data warehousing
- Alerting rules on telemetry values
- A user interface
- Building the legacy polling system itself (see open decision 4)

## 6. Capacity estimates

All inputs below are **[assumption]** until confirmed.

| Input | Value |
|---|---|
| Devices | 5 million |
| Peak rate | 50,000 events/sec |
| Average rate | 15,000 events/sec |
| Event size | 500 bytes as JSON |

| Derived figure | Value |
|---|---|
| Peak ingress | 25 MB/sec |
| Events per day | about 1.3 billion |
| Raw data per day | about 650 GB before compression |
| Kafka storage, 7 days, 3 copies | about 13.6 TB before compression |
| Latest-status rows | 5 million (one per device) |

**Consequence for the data layer.** PostgreSQL can hold 5 million status rows easily. It cannot sensibly hold 1.3 billion raw events a day. Phase 1 must therefore decide what PostgreSQL actually stores: the options are status changes only, a short retention window with old partitions dropped, or aggregated telemetry, with Kafka as the short-term record of raw events.

## 7. Disaster recovery

| Failure | Expected behaviour | Target |
|---|---|---|
| One service instance | Other instances take over its partitions | Recovery < 30s, no loss |
| One Kafka broker | Partition leadership moves to a replica | Recovery < 30s, no loss |
| Database primary | Processing pauses and events wait in Kafka, then resume | No loss; lag clears after failover |
| Redis | Rate limiting falls back to a local limit; status reads fall back to PostgreSQL | Degraded, not down |
| Whole region | Traffic moves to the other region; devices resend unacknowledged events | RTO < 60s, no loss end to end |

## 8. Architecture decisions

### ADR-001: Streaming platform

| Option | For | Against |
|---|---|---|
| **Apache Kafka** | Ordered, replayable log; per-key ordering; very high throughput; mature tooling | Operational weight; partition count is hard to reduce later |
| Apache Pulsar | Built-in geo-replication and multi-tenancy; storage separate from brokers | More moving parts; smaller ecosystem |
| RabbitMQ | Simple; flexible routing | Lower throughput; replay and per-key ordering are weaker |
| Managed cloud stream | Nothing to operate | Vendor lock-in; cannot run on a laptop |

**Decision: Kafka.** The requirement set (per-device ordering, replay for dead letters, 50,000 events/sec) is exactly what a partitioned log provides.

### ADR-002: Delivery semantics

| Option | For | Against |
|---|---|---|
| **At-least-once with idempotent consumers** | Works across Kafka, PostgreSQL, and Redis; simple to reason about | Every write must be safe to repeat |
| Kafka exactly-once transactions | No duplicates inside Kafka | Does not cover writes to PostgreSQL or Redis; adds latency |
| At-most-once | Fastest | Loses events; violates RPO = 0 |

**Decision: at-least-once with idempotent consumers.** Kafka transactions stop at Kafka's edge, and this pipeline's results live in a database.

### ADR-003: Ingestion protocol

| Option | For | Against |
|---|---|---|
| **HTTP first, protocol-neutral core** | Simple to build, secure, and load test; batching is easy | Heavier per message than MQTT for constrained devices |
| MQTT first | The standard for real device fleets; persistent connections | Needs a broker and connection-state handling; harder to benchmark |
| Both from the start | Most realistic | Doubles the gateway work before the pipeline exists |

**Decision: HTTP first.** The gateway's core takes a protocol-neutral event, so an MQTT adapter can be added without touching the pipeline.

### ADR-004: Durability and cross-region loss

| Option | For | Against |
|---|---|---|
| Synchronous replication across regions | Zero loss with no help from devices | Adds cross-region round trips to every write; breaks the latency target |
| **Synchronous within a region, asynchronous across, with device resend** | Meets the latency target; zero loss end to end | Devices must buffer unacknowledged events; duplicates after failover |
| Asynchronous everywhere | Fastest | Can lose acknowledged events |

**Decision: the second option.** Three copies within a region, with a write confirmed by at least two. Duplicates after failover are already handled by ADR-002.

### ADR-005: Ordering and late events

**Decision:** partition by device ID so one device's events stay in order. Each event carries a per-device sequence number. The status update applies only if the incoming sequence is higher than the stored one, so a late or replayed event can never overwrite newer state.

## 9. Open decisions for you

1. **Tenants.** I have assumed devices belong to tenants (customers or fleets) and that rate limits apply per tenant as well as per device. Correct?
2. **Offline detection (FR-13).** Should the platform mark devices offline when heartbeats stop? It is the feature that most directly replaces polling. Recommendation: yes.
3. **What PostgreSQL stores.** Given 1.3 billion events a day, which is it: status changes only, a short raw-event window, or aggregates? Recommendation: latest status plus a 7-day window of raw telemetry in time partitions, with the laptop running a scaled-down volume.
4. **Legacy system.** Do we include a small legacy poller and a cut-over story (run both, compare, switch), or only the new pipeline? Recommendation: only the new pipeline, with the migration approach documented.
5. **Capacity inputs.** Are 5 million devices, 500-byte events, and a 15,000 events/sec average reasonable for the story you want to tell?
6. **Multi-region.** Design it fully and simulate two regions locally in a later phase, or design only? Recommendation: design now, simulate in Phase 6.
