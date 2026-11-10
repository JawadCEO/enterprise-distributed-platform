# iot-telemetry-pipeline: Non-Functional Requirements

**Status:** draft for discussion. Each target says how it is measured and whether the laptop can verify it. "Projected" means the design is built for the target, the laptop figure is measured, and the full-scale figure is extrapolated from it.

## 1. Service level objectives

| ID | Area | Target | How it is measured | Verified on laptop |
|---|---|---|---|---|
| NFR-1 | Throughput | Full scale: 50,000 events/sec at peak, 15,000 on average **[assumption, Q5]**. Laptop: at least 5,000 events/sec **[assumption]** | Sustained for 10 minutes with consumer lag not growing | Laptop target: yes. Full scale: projected |
| NFR-2 | Freshness latency | p95 < 50ms, p99 < 150ms | From the gateway receiving an event to its status being readable, measured with trace timestamps at each hop | Yes, at laptop load |
| NFR-3 | Ingest latency | p99 < 30ms for the gateway's acknowledgement | Gateway request duration, including the Kafka confirmation | Yes |
| NFR-4 | Read latency | p99 < 20ms for current status, < 200ms for one page of history | `device-status-api` request duration | Yes |
| NFR-5 | Availability | 99.99% for ingestion and status reads | Successful requests over valid requests, per 30 days. This allows about 4.3 minutes of downtime a month. | Designed; single-region failure drills only |
| NFR-6 | Durability | RPO = 0: no acknowledged event is lost | Drill: the simulator records every acknowledged event, and the stored results are reconciled against it after each failure | Yes, within one region |
| NFR-7 | Recovery | RTO < 30s for a node, < 60s for a region | From failure until processing resumes at full rate | Node: yes. Region: simulated in Phase 6 **[assumption, Q6]** |
| NFR-8 | Offline detection | Within the heartbeat window plus 30s | Time from a device's last event to its offline status change | Yes |

## 2. Latency budget (p95, single region)

NFR-2 is only credible if each hop has a budget.

| Hop | Budget |
|---|---|
| Gateway: authenticate, validate, rate-limit check | 5ms |
| Kafka produce, confirmed by 2 of 3 replicas | 10ms |
| Consumer fetch and batching delay | 15ms |
| PostgreSQL status upsert and telemetry insert (batched) | 12ms |
| Redis status cache write | 3ms |
| Headroom | 5ms |
| **Total** | **50ms** |

The consumer's batching delay is the main lever: larger batches raise throughput and also raise latency. The batch size is set in Phase 3.

## 3. Correctness

| ID | Property | Target |
|---|---|---|
| NFR-9 | Ordering | Per device. There is no ordering guarantee across devices. |
| NFR-10 | Delivery | At least once, processed idempotently. The stored result is the same as if each event were processed exactly once. |
| NFR-11 | Consistency | A status read is never more than 1s behind PostgreSQL. The cache is written after the database, and on any Redis error the read goes to PostgreSQL. |

## 4. Scalability

| ID | Property | Target |
|---|---|---|
| NFR-12 | Horizontal scaling | Adding gateway or processor instances raises throughput with no code change, up to one processor per partition |
| NFR-13 | Headroom | The partition count supports 3x peak (150,000 events/sec) without repartitioning |
| NFR-14 | Fleet growth | 10x the devices (50 million) needs more capacity, not a new design |
| NFR-24 | Back-pressure | When Kafka is slow, the gateway caps in-flight produce requests and sheds load with 503 and `Retry-After`. It never queues unboundedly in memory, and its heap stays flat during a broker outage. |

## 5. Security

| ID | Property | Target |
|---|---|---|
| NFR-15 | Device authentication | Every event comes from an authenticated device. The method is decided in Phase 4. |
| NFR-16 | Transport | TLS from device to gateway, and TLS between services in the Kubernetes deployment |
| NFR-17 | Secrets | Device credentials are stored only as hashes. Service secrets live in Kubernetes secrets, never in the repository. |
| NFR-18 | Tenant isolation | No API path returns another tenant's data **[assumption, Q1]** |
| NFR-19 | Audit | Device registration changes and dead-letter replays are logged with who did it and when |

## 6. Observability and operability

| ID | Property | Target |
|---|---|---|
| NFR-20 | Tracing | One trace follows an event from the gateway, across Kafka, to the database write |
| NFR-21 | Metrics | Golden signals for every service, plus consumer lag per partition, dead-letter rate, and rate-limit rejections |
| NFR-22 | Deployment | Rolling deploys with no lost or duplicated status, and rollback in one step |
| NFR-23 | Resource fit | The full local stack runs in under 8GB on a 16GB laptop |

## 7. Disaster recovery

| Failure | Expected behaviour | Target |
|---|---|---|
| One service instance | Other instances take over its partitions | Recovery < 30s, no loss |
| One Kafka broker | Partition leadership moves to a replica | Recovery < 30s, no loss |
| Database primary | Processing pauses and events wait in Kafka, then processing resumes | No loss; lag clears after failover |
| Redis | Rate limiting falls back to a local limit; status reads fall back to PostgreSQL | Degraded, not down |
| Whole region | Traffic moves to the other region; devices resend unacknowledged events | RTO < 60s, no loss end to end |
| Cross-region replication lag | Only a device's home region marks it offline, so replication lag cannot cause a false offline in the other region. If the home region is lost, the surviving region takes over offline marking after one heartbeat window. | No false offline from lag |
