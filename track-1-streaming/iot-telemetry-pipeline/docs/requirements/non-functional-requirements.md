# iot-telemetry-pipeline: Non-Functional Requirements

**Status:** draft for discussion. Each target says how it is measured and whether the laptop can verify it. "Projected" means the design is built for the target, the laptop figure is measured, and the full-scale figure is extrapolated from it.

## 1. Service level objectives

| ID | Area | Target | How it is measured | Verified on laptop |
|---|---|---|---|---|
| NFR-1 | Throughput | 50,000 events/sec at peak, 15,000 on average **[assumption, Q5]**. Laptop pass target: 5,000 events/sec | Sustained for 10 minutes with consumer lag not growing | Laptop target: yes. Full scale: projected |
| NFR-2 | Freshness latency | p95 < 50ms, p99 < 150ms | From the gateway receiving an event to the line's new state being readable, measured with trace timestamps at each hop | Yes, at laptop load |
| NFR-2a | Notification latency | p95 < 2s from the event to the webhook leaving the platform | Notifier send time minus gateway receive time, to a healthy test endpoint | Yes |
| NFR-2b | Request completion | p95 < 5s from `202` to `COMPLETED` when the simulated network answers in 1s | Request timestamps | Yes |
| NFR-3 | Ingest latency | p99 < 30ms for the gateway's acknowledgement | Gateway request duration, including the Kafka confirmation | Yes |
| NFR-4 | Read latency | p99 < 20ms for a line's current state, < 200ms for one page of history | `customer-api` request duration | Yes |
| NFR-5 | Availability | 99.99% for ingestion and status reads | Successful requests over valid requests, per 30 days. This allows about 4.3 minutes of downtime a month. | Designed; single-region failure drills only |
| NFR-6 | Durability | RPO = 0: no acknowledged event or accepted request is lost | Drill: the simulator records every acknowledged event, and the stored results are reconciled against it after each failure | Yes, within one region |
| NFR-7 | Recovery | RTO < 30s for a node, < 60s for a region | From failure until processing resumes at full rate | Node: yes. Region: simulated in Phase 6 **[assumption, Q6]** |
| NFR-8 | Offline detection | Within the keep-alive window plus 30s | Time from a device's last event to its offline state change | Yes |
| NFR-8a | Usage accuracy | Every line's cycle total equals the simulator's ledger exactly, after every drill | Reconciliation job | Yes |

## 2. Latency budget (p95, single region)

NFR-2 is only credible if each hop has a budget.

| Hop | Budget |
|---|---|
| Gateway: authenticate, validate, rate-limit check | 5ms |
| Kafka produce, confirmed by 2 of 3 replicas | 10ms |
| Consumer fetch and batching delay | 15ms |
| PostgreSQL state upsert and history insert (batched) | 12ms |
| Redis state cache write | 3ms |
| Headroom | 5ms |
| **Total** | **50ms** |

The consumer's batching delay is the main lever: larger batches raise throughput and also raise latency. The batch size is set in Phase 3.

## 3. Correctness

| ID | Property | Target |
|---|---|---|
| NFR-9 | Ordering | Per device. There is no ordering guarantee across devices. |
| NFR-10 | Delivery | At least once, processed idempotently. The stored result is the same as if each event were processed exactly once. Usage is counted exactly once (ADR-006). |
| NFR-10a | Webhooks | At least once, each with a stable `notificationId` so customers can drop repeats. Order is kept per line, not across lines. |
| NFR-11 | Consistency | State reads may lag writes by at most the freshness target. A cached state is never older than its PostgreSQL row by more than the cache refresh path. |

## 4. Scalability

| ID | Property | Target |
|---|---|---|
| NFR-12 | Horizontal scaling | Adding gateway or processor instances raises throughput with no code change, up to one processor per partition |
| NFR-13 | Headroom | The partition count supports 3x peak (150,000 events/sec) without repartitioning |
| NFR-14 | Fleet growth | 10x the devices (50 million) needs more capacity, not a new design |

## 5. Security

| ID | Property | Target |
|---|---|---|
| NFR-15 | Authentication | Every event comes from an authenticated device or network source; every request from an authenticated customer. Methods are decided in Phase 4. |
| NFR-16 | Transport | TLS from device to gateway, and TLS between services in the Kubernetes deployment |
| NFR-17 | Secrets | Device credentials are stored only as hashes. Service secrets live in Kubernetes secrets, never in the repository. |
| NFR-18 | Customer isolation | No API path or webhook exposes another customer's lines **[assumption, Q1]** |
| NFR-18a | Webhook signing | Every webhook is signed (HMAC with a per-customer secret) so customers can verify it |
| NFR-19 | Audit | Line requests, registry changes and dead-letter replays are logged with who did it and when |

## 6. Observability and operability

| ID | Property | Target |
|---|---|---|
| NFR-20 | Tracing | One trace follows an event from the gateway, across Kafka, to the database write |
| NFR-21 | Metrics | Golden signals for every service, plus consumer lag per partition, dead-letter rate, rate-limit rejections, request outcomes, and webhook delivery success and backlog per customer |
| NFR-24 | Back-pressure | When consumers fall behind, the gateway keeps accepting while Kafka has room; past a lag threshold it answers `503` with `Retry-After` instead of failing slowly |
| NFR-22 | Deployment | Rolling deploys with no lost or duplicated status, and rollback in one step |
| NFR-23 | Resource fit | The full local stack runs in under 8GB on a 16GB laptop |

## 7. Disaster recovery

| Failure | Expected behaviour | Target |
|---|---|---|
| One service instance | Other instances take over its partitions | Recovery < 30s, no loss |
| One Kafka broker | Partition leadership moves to a replica | Recovery < 30s, no loss |
| Database primary | Processing pauses and events wait in Kafka, then processing resumes | No loss; lag clears after failover |
| Redis | Rate limiting falls back to a local limit; state reads fall back to PostgreSQL; duplicate checks fall back to the database key | Degraded, not down |
| Simulated network provisioning | Requests wait and retry, then fail with `NETWORK_TIMEOUT`; events keep flowing | Requests fail cleanly; no stuck `PENDING` |
| A customer's webhook endpoint | That customer's notifications queue and retry; others are unaffected | No effect on other customers |
| Whole region | Traffic moves to the other region; devices resend unacknowledged events | RTO < 60s, no loss end to end |
