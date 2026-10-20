# pulselink-platform: Problem Statement

**Status:** draft for discussion. Items marked **[assumption]** are working assumptions taken from the recommendations in [open-questions.md](open-questions.md) and need confirmation.

## 1. Context

In a hospital ward, every monitored patient is connected to a bedside monitor that measures vital signs continuously: heart rate, oxygen saturation (SpO2), blood pressure, respiratory rate, and temperature. The monitor's job is to measure. Deciding that a reading is dangerous, finding the clinician responsible for that bed, getting their attention, and making sure someone acts falls to the alarm system behind the monitors.

That system fails in three well-documented ways.

| Failure mode | What happens | Consequence |
|---|---|---|
| **Lost or late alerts** | The alert depends on a central server, a database write, or a single network path. When that component is slow or down, the alert waits or disappears. | A deteriorating patient is not seen in time. |
| **Open loop** | An alert is displayed, but nothing records whether a clinician saw it or acted on it. If the assigned nurse is busy, nobody else is told. | The alert exists, but nobody acts on it. |
| **Alarm fatigue** | The same condition raises the same alarm repeatedly, and noisy or artefact readings raise alarms too. Published reviews report that the large majority of monitor alarms are false or not clinically actionable. | Clinicians mute or ignore alarms, and the real ones get lost in the noise. |

Alarm safety has been a recurring national patient-safety priority in the US (for example, The Joint Commission's Sentinel Event Alert 50 and its National Patient Safety Goal on clinical alarms), and the failure modes above are the ones named there.

## 2. Problem statement

> For a hospital of up to **10,000 monitored beds**, each producing a continuous stream of numeric vital-sign readings (about **100,000 readings per second** in total), build a system that:
>
> 1. **detects** every reading or short sequence of readings that matches a configured alert rule for that patient,
> 2. **delivers** the resulting alert to an on-duty clinician responsible for that bed within **100 ms at p95** (300 ms at p99) of the triggering reading arriving,
> 3. **closes the loop** by guaranteeing that every raised alert ends in an explicit acknowledgement or in escalation through every tier of the escalation policy, and is never silently dropped,
> 4. **suppresses** repeat alerts for an unchanged condition without hiding a condition that is getting worse, and
> 5. **keeps working** when any single component fails, including the vitals history store,
>
> while **separately** retaining the full reading history for charting and retrospective review, where a delay of seconds is acceptable.

The central design tension is that (2) and (5) demand a short, highly available path, while the history requirement demands high-volume durable writes. The core design principle follows from this: **the alert path must never wait on the history path.**

## 3. Why this is hard

| Challenge | Why it matters |
|---|---|
| **Latency and durability pull in opposite directions** | Every durable write or network hop protects an alert against loss but adds latency. The design must choose which hops are durable and prove the latency budget still holds. |
| **Stateful detection on a stream** | Window rules ("SpO2 below 90% for 15 seconds") need per-patient state. That state must survive an instance failure and move to another instance without missing or duplicating an alert. |
| **Exactly one alert, in practice** | At-least-once delivery means duplicates, and at-most-once means losses. Losing an alert is the worse failure, so the system accepts duplicates and removes them where it can. |
| **Time** | Readings carry a device timestamp and a receive timestamp. Clocks drift, and networks reorder and delay readings. Window rules must say which clock they use and what happens to late readings. |
| **Noise versus safety** | Suppressing repeats reduces fatigue. Suppressing too much hides deterioration. The rule for raising an alert again must be explicit and testable. |
| **Five-nines target on a laptop** | 99.999% is about 5 minutes of downtime a year. It cannot be measured in a portfolio project, so it has to be shown through redundancy and failure drills. |

## 4. Users and systems

| Actor | Needs from the system |
|---|---|
| Bedside monitor (simulated) | Push readings with low overhead, and learn when it must slow down |
| Nurse | Receive alerts for assigned beds immediately; acknowledge them; see live vitals for those beds |
| Charge nurse | Receive escalations; manage bed assignments for the shift |
| Unit physician | Receive the final tier of escalation for critical alerts **[assumption]** |
| Clinical administrator | Configure threshold profiles, patient overrides, and escalation policies |
| Auditor | Review who accessed which patient's data, and every action taken on every alert |
| Operator / SRE | See alert-path health, latency, and lag; run failure drills |

## 5. Goals and success metrics

Each goal has a metric that can be checked. Section numbers refer to [non-functional-requirements.md](non-functional-requirements.md).

| # | Goal | Success metric | Verified by |
|---|---|---|---|
| G1 | Fast alert delivery | p95 < 100 ms, p99 < 300 ms, from the gateway receiving the reading to the alert being written to the clinician's connection | Latency benchmark (Phase 8) |
| G2 | No silent alert loss | 0 raised alerts that end in neither acknowledged nor fully escalated, across all test runs and failure drills | Missed-alert test suite |
| G3 | Alerting survives failure | Alerting resumes within 5 s **[assumption]** after losing any single detector, router, or Kafka broker; history store outage has no effect on alerts | Failure drills |
| G4 | Less noise without hiding deterioration | A sustained condition raises one alert per suppression window; a worsening condition always raises again | Scripted simulator scenarios |
| G5 | Protected patient identity | 0 patient names, record numbers, or dates of birth in logs; every read of patient data audited | Log scan in CI; audit completeness test |
| G6 | Full history | Readings queryable by patient and time range; long ranges served from roll-ups | Query tests |

## 6. Scope

**In scope:** numeric vitals ingestion, per-patient threshold, window and rate-of-change rules, alert lifecycle with acknowledgement and escalation, push delivery to a web dashboard, live unit view, vitals history with roll-ups, threshold and policy configuration, access control and audit, and a simulator that produces scripted emergencies.

**Non-goals:**

- Diagnosis or clinical decision support beyond rule evaluation. Statistical or ML detection is out of scope; the rule engine sits behind an interface so a model could be added later **[assumption]**.
- High-frequency waveforms such as ECG at 250 samples per second. Numeric vitals only.
- Integration with hospital record systems (HL7, FHIR).
- Medical device certification or regulatory submission.
- Real patient data of any kind. All data is synthetic.
- Mobile apps, pagers, and nurse-call hardware. Alerts go to a web dashboard over WebSocket.

## 7. Constraints

| Constraint | Effect on the design |
|---|---|
| Built and benchmarked on one 16 GB laptop | The full stack must fit in about 6 GB. Measured results are for a few hundred beds; 10,000-bed figures are projections from measured per-instance throughput. |
| Stack: Java, Spring WebFlux, Kafka, TimescaleDB / PostgreSQL, Redis, gRPC / WebSocket, Kubernetes | Fixed by the project brief. |
| HIPAA-aligned, not HIPAA-compliant | Compliance is an organisational certification a portfolio project cannot claim. The design follows its technical safeguards: access control, audit, encryption, minimum necessary data in logs. |

## 8. Working assumptions

| ID | Assumption | Source |
|---|---|---|
| A-1 | The alert path goes through Kafka on both hops (gateway to detector, detector to router) for durability and failover | Open question 1 |
| A-2 | Detection is rule-based only | Open question 2 |
| A-3 | Monitors send over gRPC bidirectional streaming; dashboards use WebSocket | Open question 3 |
| A-4 | Raw readings are kept 30 days, one-minute roll-ups 1 year, one-hour roll-ups 7 years | Open question 4 |
| A-5 | Critical alerts escalate after 30 s, high after 2 min, medium after 5 min; advisory alerts do not escalate | Open question 5 |
| A-6 | 99.999% is a design target shown through redundancy and drills, not a measured figure | Open question 6 |
