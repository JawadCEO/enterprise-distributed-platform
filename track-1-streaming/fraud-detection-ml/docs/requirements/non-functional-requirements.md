# fraud-detection-ml: Non-Functional Requirements

**Status:** draft for discussion. Items marked **[assumption]** need confirmation.

Every target below states where it is measured and how, so that a result can be checked rather than claimed.

## 1. Targets

| ID | Area | Target | Measured how |
|---|---|---|---|
| NFR-1 | Latency | p95 < 25ms, p99 < 50ms, p99.9 reported | At the scoring service boundary (request received to response written), at 10,000 payments/sec, steady state for 10 minutes |
| NFR-2 | Throughput | 10,000 payments/sec sustained | Open-loop load from the simulator (see section 3) |
| NFR-3 | Availability | 99.999% of valid requests answered within the 100ms caller deadline **[assumption]** | A fallback or degraded decision counts as answered; an error, a timeout, or no response counts as down. 99.999% allows 1 failure per 100,000 requests, or about 26 seconds of full outage per month |
| NFR-4 | Inference budget | 15ms **[assumption]** | Past this, the rule engine answers (FR-8) |
| NFR-5 | Feature freshness | p99 < 1 second **[assumption]** | From a payment's response to its effect being readable in Redis, using timestamps carried on the event |
| NFR-6 | Decision durability | No decision record lost once acknowledged by Kafka; bounded loss only if the local buffer overflows during a broker outage, and that loss is counted | Reconciliation of response count, event count, and database row count after every test |
| NFR-7 | Explanation lag | p99 < 5 seconds from response to stored attributions **[assumption]** | Timestamps on decision and attribution rows |
| NFR-8 | Model quality | ROC-AUC > 0.96; recall ≥ 60% at FPR ≤ 0.1% **[assumption]**; PR-AUC reported | Time-based holdout (train on earlier months, test on later ones); never a random split |
| NFR-9 | Training-serving parity | Identical feature values, within floating-point tolerance | FR-27 and FR-28 run in CI |
| NFR-10 | Model rollback | Under 10 seconds, no restart, zero failed requests | Drill under load |
| NFR-11 | Recovery | Scoring service instance restart to ready in under 5 seconds, with the model loaded | Kill test |
| NFR-12 | Resource fit | The whole stack runs on a 16GB laptop within about 6GB, leaving room for the OS, IDE, and load generator | Container memory limits; peak usage recorded in every benchmark |

## 2. Why the holdout is split by time

Fraud patterns change over time. A random split lets the model learn from payments that happened after the ones it is tested on, which leaks the future and overstates accuracy. Training on earlier months and testing on later ones gives the figure a real deployment would see. Labels for the most recent payments may not have arrived yet, so the holdout ends at a label cut-off date.

## 3. Latency budget and measurement

| Step | Budget (p95) |
|---|---|
| Request parsing and validation | 1ms |
| Idempotency check and feature fetch from Redis (one pipelined round trip) | 3ms |
| Request-time features | 0.5ms |
| Inference (with rule engine in parallel) | 15ms |
| Decision, reason codes, response | 0.5ms |
| Event publish (asynchronous, not on the response path) | 0ms |
| Headroom (garbage collection, scheduling) | 5ms |
| **Total** | **25ms** |

The inference budget is generous: a few hundred trees on about 40 features should score in well under 1ms in Go. The budget exists to cap the tail, not because inference is expected to be slow. Measured stage latencies replace these estimates in Phase 6.

How latency is measured:

- **Open-loop load.** The simulator sends at a fixed rate regardless of how fast responses come back. A closed-loop tool that waits for each response before sending the next hides the slow periods ("coordinated omission") and reports a misleadingly good tail.
- **Histograms, not averages.** Latency is recorded in HDR histograms per stage; averages are never reported as the headline.
- **Warm-up excluded.** The first 60 seconds of each run are discarded.
- **Same machine, stated.** Load generator and system share the laptop; the report states this, because it costs the system CPU.

## 4. Scalability and capacity

| Figure | Value |
|---|---|
| Peak rate | 10,000 payments/sec |
| Payments per day at peak | about 864 million |
| Scored-payment event size | about 1.5KB (payment plus about 40 features) |
| Kafka write rate at peak | about 15MB/s |
| Decision rows per day at peak | about 864 million, so the table is partitioned by day |
| Online feature store | about 2KB per active card including window buckets; 5 million cards fit in about 10GB of Redis (production), 100,000 cards in about 200MB (laptop) |

Scaling approach: the scoring service is stateless apart from the loaded model, so it scales by adding instances behind a load balancer. The feature pipeline scales by Kafka partitions (partitioned by card token). Redis scales by sharding on the entity key. If one laptop cannot reach 10,000 payments/sec, the report gives the measured per-instance throughput and a projection to 10,000, clearly separated from what was measured.

## 5. Failure modes and expected behaviour

| Failure | Expected behaviour | Test |
|---|---|---|
| Model slow or unavailable | Rule engine answers; `fallback = true` with cause | Inject inference delay |
| Redis unavailable or slow (over 3ms) | Default features plus rule engine; `degraded = true` | Stop Redis under load |
| Kafka unavailable | Scoring continues; events buffered locally (bounded, about 60 seconds at peak) and sent on recovery; overflow counted | Stop Kafka under load |
| Feature pipeline lagging | Scoring continues with stale features; freshness alert fires | Pause the consumer |
| Bad model promoted | Rollback in under 10 seconds | Promote a deliberately bad model in a drill |
| Scoring instance crash | Load balancer routes to other instances; in-flight requests on that instance fail and the caller retries with the same transaction ID | Kill an instance under load |
| Duplicate request | Original decision returned (FR-10) | Replay test |

## 6. Security and privacy

| ID | Requirement |
|---|---|
| NFR-13 | No primary account numbers anywhere: cards are represented only by tokens, which keeps the system out of payment-card security scope by construction |
| NFR-14 | Service-to-service calls authenticated; the scoring API requires a client credential |
| NFR-15 | Decision records are append-only; corrections are new records, so the audit trail cannot be rewritten |
| NFR-16 | Access to decisions and labels is role-based (analyst, data scientist, operator) |
| NFR-17 | Secrets come from the environment or a secret store, never from the repository |

## 7. Observability

| ID | Requirement |
|---|---|
| NFR-18 | Traces across scoring, publish, feature update, and recording, using OpenTelemetry |
| NFR-19 | Metrics: latency by stage, request rate, decision mix, fallback and degraded rates, feature freshness, consumer lag, buffer depth |
| NFR-20 | Model monitoring: score distribution and feature distributions compared with training (population stability index), alert on drift |
| NFR-21 | Structured logs carrying transaction ID and trace ID |

## 8. Maintainability

| ID | Requirement |
|---|---|
| NFR-22 | Feature definitions live in one specification file that both the Go and Python implementations are tested against |
| NFR-23 | Unit, contract, parity, and failure tests run in CI; the latency benchmark runs on demand |
| NFR-24 | Each component builds and starts with one command; the full stack starts with Docker Compose |

## 9. Laptop memory budget (16GB)

| Component | Budget |
|---|---|
| Kafka (single broker, KRaft) | 1.5GB |
| Redis | 0.5GB |
| PostgreSQL | 1GB |
| Scoring service (2 instances) | 0.5GB |
| Feature pipeline | 0.3GB |
| Decision recorder (Python) | 0.7GB |
| Simulator | 0.5GB |
| Observability (collector, Prometheus, Grafana) | 1GB |
| **Total** | **about 6GB** |

Training on the full dataset runs separately, not alongside the live stack, and may need a sample if 24 million rows do not fit in memory with the chosen features.
