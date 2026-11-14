# iot-telemetry-pipeline: Problem Statement

**Status:** agreed. Items marked **[decided, Qn]** follow the answer to question n in [open-questions.md](open-questions.md) and change if that answer changes. How the platform works end to end, with examples, is in [how-it-works.md](how-it-works.md).

## 1. Context

A mobile carrier runs an **IoT connectivity platform**, like Verizon ThingSpace. It provides cellular lines (SIMs) to business customers for their connected devices: a logistics company's asset trackers, a utility's smart meters, a cold-chain firm's fridge sensors. About **5 million lines** are active across many customers **[decided, Q5]**.

Each line is a SIM in one of three forms: a **removable** card, an **embedded eSIM** soldered to the device's board, or an **integrated iSIM** built into the modem chip. The form matters: a soldered or integrated SIM can't move to another device, and its carrier profile is changed over the air instead of by swapping a card.

Customers need to know, for every line, whether the device is online, its battery and signal, how much data it has used, and whether their requests (activate, suspend, reconnect, deactivate, switch profile) have gone through. The carrier's operations team needs the same picture for the whole fleet.

## 2. The problem

> Customers and the carrier learn about device and line changes by **polling**. Changes are seen minutes late, the cost grows with the number of lines rather than with how often anything changes, and one slow region or one large customer delays everyone else.

Concretely, with a 5-minute polling interval across 5 million lines:

| What customers do today | Why it hurts | Illustrative impact |
|---|---|---|
| Call the status API for all their lines on a schedule to see which devices are online | Almost every answer is "no change" | ~16,700 requests/sec, nearly all wasted |
| Call the request API again and again after asking to reconnect a line, until it says done | Slow to learn the outcome; adds more load | A 30 s reconnect takes up to 5 minutes to be noticed |
| Download usage reports to catch a line running out of data | Overage is noticed after the fact | Customers are billed for overage they could have prevented |
| Find out a device died only when the next poll fails, then confirm with more polls | A missed poll is ambiguous: slow, or gone? | Offline confirmed only after 10 to 15 minutes |
| Nothing at all tells them a SIM appeared in a different device | Theft or cloning goes unnoticed | A stolen SIM uses data for days |

Polling faster improves freshness but multiplies the cost, so tuning can't fix it. The flow has to be inverted: **the platform learns about changes as they happen and tells the customer.**

## 3. Who is affected

| Stakeholder | What they need | Pain today |
|---|---|---|
| Customer systems (integrations) | Line state, device status and request outcomes for their own lines, pushed to them | Constant polling; stale answers; no change notifications |
| Customer operations staff | Know within a minute when a device goes offline unexpectedly or a SIM moves | They learn late, often from their own end customers |
| Carrier support engineers | See what a line reported and when, and every state change, to diagnose a ticket | History is sparse and exists only at poll times |
| Carrier platform team | Run at 5M lines and beyond; contain faults and noisy customers | Polling load grows with the fleet; one bulk job slows everyone |
| Carrier fraud team | Hear about SIMs appearing in unexpected devices | No signal at all |

## 4. Proposed direction

Devices and the carrier network **push events** as things happen: attached, detached, status, usage, keep-alive, SIM moved, provisioning results. Customers send **requests** to change their lines. A gateway accepts each event and request, makes it durable in Kafka, and acknowledges it. Downstream services validate and de-duplicate events, apply them in order per line, keep each line's current state, carry out line changes against the network, and **notify customers by webhook** when something they care about changes. A bad event is retried or set aside without blocking others, and a noisy customer is rate limited without slowing the rest.

## 5. Goals and success metrics

Each goal has a metric that a test or drill can check.

| ID | Goal | Success metric |
|---|---|---|
| G1 | Fresh state | A line's new state is readable within 50 ms p95 and 150 ms p99 of the platform receiving the event |
| G2 | Customers stop polling | Every state change a customer subscribed to is delivered as a webhook within 2 s p95 of the event, with no polling needed |
| G3 | Fast, honest offline detection | An unexpectedly silent device is reported offline within its keep-alive window plus 30 s. Expected silences (suspended line, profile switch in progress) and region-wide outages produce no per-line alerts |
| G4 | Requests complete reliably | Every accepted request ends `COMPLETED` or `FAILED` with a reason, never stuck; repeats with the same idempotency key change nothing |
| G5 | Exact usage | Each usage record is counted exactly once, verified by reconciling totals against the simulator's ledger after failure drills |
| G6 | No loss | Zero acknowledged events or requests lost across broker, database and service-kill drills |
| G7 | Isolation | A customer sending 10x its rate limit leaves other customers' latency within G1; one bad event delays later events on the same line by at most one retry hop |
| G8 | Scale | 50,000 events/sec sustained for 10 minutes with flat consumer lag (design target; the laptop figure is measured and projected) **[decided, Q5]** |

## 6. Non-goals

- Billing, invoicing and rating. Usage totals are produced for the billing system; billing itself is out of scope.
- Analytics or machine learning on device readings, and alert rules on sensor values (e.g. "temperature above 8°C")
- Sending application commands or firmware to devices
- The real carrier network and SIM provisioning systems. They're simulated.
- A user interface
- Health or medical devices (a separate project, PulseLink)
- Building the legacy polling system. The cut-over is documented, not built. **[decided, Q4]**

## 7. Constraints

| Constraint | Effect |
|---|---|
| All development and benchmarks run on a 16 GB laptop | Full-scale figures are design targets. Local results are reported as measured, with a projection. |
| Stack: Java, Spring Boot, Kafka, PostgreSQL, Redis, Kubernetes, OpenTelemetry | Fixed for this project |
| No carrier publishes network data | All traffic comes from `network-simulator`, with faults injected on purpose so every scenario repeats |
| Multi-region can't run on one laptop | Designed fully, then simulated with two local clusters in Phase 6 **[decided, Q6]** |

## 8. The hard parts

1. **Ordering with retries and reboots.** Retried events arrive late, and a rebooted device restarts its sequence at 1. Ordering uses a boot counter plus sequence per line (ADR-005).
2. **Counting usage exactly once** on an at-least-once pipeline. A duplicate usage record overcharges a customer (ADR-006).
3. **Requests that take seconds to minutes.** A reconnect waits on the network, which is slow and sometimes fails. Each request is a small state machine that must never get stuck (ADR-007).
4. **Telling expected silence from real outages.** Suspended lines and profile switches go quiet on purpose; a cell outage silences thousands of devices at once. Neither should flood customers with alerts.
5. **Webhook delivery to systems we don't control.** Customer endpoints are slow or down. Delivery must retry without blocking other customers (ADR-008).
6. **Hot keys and bulk jobs.** One customer suspending 50,000 lines, or one chatty device, must not starve others.
7. **Durability versus latency across regions.** Zero loss without a synchronous cross-region write depends on devices and the network resending (ADR-004).
