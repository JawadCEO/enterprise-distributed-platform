# iot-telemetry-pipeline: Problem Statement

**Status:** draft for discussion. Items marked **[assumption, Qn]** follow the recommended answer to open question n in [open-questions.md](open-questions.md). They change if that answer changes.

## 1. Context

An operator runs a fleet of about 5 million connected devices, such as trackers, meters, and sensors, owned by many tenants **[assumption, Q1, Q5]**. Operations staff, support engineers, and tenant systems all need to know the current state of each device: online or offline, battery, signal, firmware, and fault status.

Today a central scheduler learns that state by polling, asking every device for its status on a fixed interval.

## 2. The problem

> Operators see a change in device state only seconds or minutes after it happens. The cost of finding out grows with the size of the fleet, not with how often devices actually change. And a slow or unreachable group of devices makes every other device's data stale too.

Polling ties cost and freshness to the polling interval rather than to how often devices change. The figures below are illustrative and assume a 5-minute interval across 5 million devices.

| Symptom | Mechanism | Illustrative impact |
|---|---|---|
| Stale state | A change is seen only at the next poll | Mean detection delay 2.5 min, worst case 5 min |
| Wasted capacity | Every device is polled whether or not anything changed | About 16,700 polls/sec all the time, most returning "no change" |
| Correlated degradation | Slow or unreachable devices hold connections and stretch the cycle | One regional outage delays freshness for the whole fleet |
| Slow offline detection | A missed poll is ambiguous (is the device slow, or gone?), so several misses are needed to confirm | Offline is confirmed only after several intervals |

Shortening the interval improves freshness but multiplies the cost, so tuning cannot fix this. The flow has to be inverted.

## 3. Who is affected

| Stakeholder | What they need | Pain today |
|---|---|---|
| Fleet operations | Know within seconds when devices go offline or report faults | They learn minutes late, and outages surface through customers |
| Tenant integrations | Query the current status and recent history of their own devices through an API | Answers are stale and there are no change notifications |
| Support engineers | See what a device reported and when, to diagnose issues | History is sparse and exists only at poll times |
| Platform team | Run the system at scale, and contain faults and noisy tenants | Polling load grows with the fleet, and a bad batch stalls the cycle |

## 4. Proposed direction

Devices push events when something happens, plus a periodic heartbeat. A gateway accepts each event, makes it durable in a partitioned log, and acknowledges it. Downstream consumers then validate and de-duplicate events and apply them in order per device. They keep the latest status and treat silence as "offline". Failures are isolated. A bad event is retried or set aside without blocking others, and a noisy sender is rate limited without starving everyone else.

## 5. Goals and success metrics

Each goal has a metric that a test or a drill can check.

| ID | Goal | Success metric |
|---|---|---|
| G1 | Fresh status | A new status is queryable within 50ms p95 and 150ms p99 of the gateway receiving the event |
| G2 | Fast offline detection | A silent device is marked offline within its heartbeat window plus 30s **[assumption, Q2]** |
| G3 | Throughput | 50,000 events/sec sustained for 10 minutes with flat consumer lag. This is a design target; the laptop figure is measured and projected. |
| G4 | No loss | Zero acknowledged events lost across broker, database, and service-kill drills |
| G5 | Fault isolation | A malformed event delays later events for the same device by no more than one retry hop. A tenant sending 10x its limit leaves other tenants' latency within G1. |
| G6 | Cost follows change | An idle device costs one heartbeat per window, and nothing is polled |

## 6. Non-goals

- Sending commands or configuration to devices
- Firmware updates and device provisioning hardware flows
- Analytics, dashboards, and long-term data warehousing
- Alerting rules on telemetry values
- A user interface
- Building the legacy polling system. The cut-over is documented, not built. **[assumption, Q4]**

## 7. Constraints

| Constraint | Effect |
|---|---|
| All development and benchmarks run on a 16GB laptop | Full-scale figures are design targets. Local results are reported as measured, with a projection. |
| Stack: Java, Spring Boot, Kafka, PostgreSQL, Redis, Kubernetes, OpenTelemetry | Fixed for this project |
| Synthetic data only, from `device-simulator` | Faults are injected on purpose, so every scenario is repeatable |
| Multi-region cannot run on one laptop | Designed fully, then simulated with two local clusters in Phase 6 **[assumption, Q6]** |

## 8. The hard parts

1. **Durability versus latency across regions.** Zero loss without a synchronous cross-region write depends on devices resending events (ADR-004).
2. **Database write volume.** About 1.3 billion events a day cannot all live in PostgreSQL for long, so what it stores is a deliberate choice **[assumption, Q3]**.
3. **Ordering with non-blocking retries.** Retried events arrive out of order. Correctness relies on per-device sequence numbers (ADR-005).
4. **Hot keys.** One chatty device or tenant can overload a partition. Rate limits at the gateway contain it.
5. **Untrusted device clocks.** Device timestamps can be wrong. Ordering uses sequence numbers, and the platform records its own receive time.
