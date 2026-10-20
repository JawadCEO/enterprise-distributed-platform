# pulselink-platform: Functional Requirements

**Status:** draft for discussion. Items marked **[assumption]** are working assumptions and need confirmation.

Each requirement states what the system shall do, its priority, and the observable behaviour that proves it is met. Priorities: **Must** (the system is not fit for purpose without it), **Should** (expected, can slip one phase), **Could** (only if time allows).

## 1. Ingestion

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-01 | The system shall accept a continuous stream of vitals frames from each bedside monitor over gRPC bidirectional streaming **[assumption]**. A frame carries one or more readings: heart rate, SpO2, systolic and diastolic blood pressure, respiratory rate, temperature. | Must | A simulated monitor holds one long-lived stream and sends frames at 2 Hz **[assumption]**; every valid frame is accepted and published. |
| FR-02 | The system shall validate each frame: known monitor, known metric, value within the physically possible range for that metric, unit of measure matching the metric, device timestamp present. | Must | Invalid frames are rejected with a reason code on the stream and sent to a dead-letter stream; valid frames in the same stream are unaffected. |
| FR-03 | The system shall acknowledge accepted frames on the stream only after they are durably published, so a monitor knows what it may discard. | Must | A frame acknowledged to the monitor is present in the vitals stream after a gateway crash. |
| FR-04 | The system shall signal back-pressure to a monitor that sends faster than it can be processed, using gRPC flow control, instead of buffering without limit. | Should | Under overload, gateway memory stays bounded and the monitor's send rate drops. |

## 2. Patient context

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-05 | The system shall link each bed's readings to the patient currently admitted to that bed. | Must | Every reading for an occupied bed carries the internal patient ID. |
| FR-06 | The system shall handle admission, transfer, and discharge. After a transfer, readings from the new bed apply to the patient and detection continues with that patient's thresholds. | Must | A scripted transfer produces no alert gap and no alert under the wrong patient. |
| FR-07 | Readings from a bed with no admitted patient shall be stored against the bed but shall not raise clinical alerts. Technical alerts still apply. | Should | An unoccupied bed produces history rows but no clinical alerts. |

## 3. Detection

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-08 | The system shall evaluate every reading against threshold rules. Thresholds come from the unit's default profile, overridden per patient where an override exists. | Must | A reading outside the effective limit raises an alert; changing a patient override changes the outcome for the next reading. |
| FR-09 | The system shall evaluate time-window rules, for example "SpO2 below 90% for 15 seconds", using device time **[assumption]**. | Must | A scripted SpO2 drop raises the alert when the window is satisfied, not before. |
| FR-10 | The system shall evaluate rate-of-change rules, for example "heart rate rises by more than 30 bpm within 60 seconds". | Should | A scripted rapid rise raises an alert while every individual reading is still within thresholds. |
| FR-11 | Readings flagged by the monitor as poor signal quality shall not trigger clinical alerts. A sustained run of poor-quality readings shall raise a technical alert. | Should | A scripted artefact burst raises no clinical alert; a long artefact run raises one technical alert. |
| FR-12 | The system shall raise a technical alert when a monitor stops sending for longer than a configured gap, 10 seconds by default **[assumption]**. | Must | Killing a simulated monitor raises a "signal lost" alert within the gap plus the alert latency target. |
| FR-13 | Each alert shall carry a severity: critical, high, medium, or advisory, set by the rule that fired. | Must | Every alert has exactly one severity. |
| FR-14 | Detection rules shall sit behind a single rule interface, so a new rule type can be added without changing the detector's stream handling. | Should | A new rule type is added with a new class and configuration only. |

## 4. Alert noise control

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-15 | The system shall not raise a new alert for the same patient and rule while an alert for that condition is already open, or within the suppression window after it closes (120 seconds by default **[assumption]**). | Must | A condition held for 10 minutes produces one alert, not one per reading. |
| FR-16 | The system shall raise again, inside the suppression window, if the condition worsens: a higher severity, or the value moves further past the limit by more than a configured margin. | Must | A scripted SpO2 fall from 88% to 80% produces a second, higher-severity alert. |

## 5. Alert lifecycle, delivery, and escalation

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-17 | Each alert shall move through a defined lifecycle: `RAISED → DELIVERED → ACKNOWLEDGED → RESOLVED`, with `ESCALATED` possible from `RAISED` or `DELIVERED`. Every transition is recorded with time and actor. | Must | The full history of any alert can be reconstructed from its recorded events. |
| FR-18 | The system shall route each alert to every clinician currently assigned to that bed and push it to each of their open dashboard connections over WebSocket. | Must | All assigned, connected clinicians receive the alert; clinicians not assigned do not. |
| FR-19 | If no assigned clinician is connected, the alert shall go straight to the next escalation tier. | Must | An alert for a bed whose nurse is offline reaches the charge nurse without waiting for the timeout. |
| FR-20 | A clinician shall be able to acknowledge an alert from the dashboard. The first acknowledgement wins; others see who acknowledged it. | Must | Two clinicians acknowledging together produce one acknowledgement event and a consistent view for both. |
| FR-21 | If an alert is not acknowledged within its policy timeout, the system shall escalate it to the next tier. Starting policy **[assumption]**: critical: assigned nurse, then charge nurse at 30 s, then unit physician at 60 s; high: charge nurse at 2 min; medium: charge nurse at 5 min; advisory: no escalation. | Must | Every unacknowledged alert escalates on time in the escalation test suite. |
| FR-22 | When a dashboard reconnects, the system shall re-send every alert for that clinician that is not yet acknowledged. | Must | Dropping and restoring a connection mid-alert shows the alert again; escalation timers keep running while the connection is down. |
| FR-23 | An alert shall be resolved when it has been acknowledged and its condition has cleared. Clearing alone does not resolve an unacknowledged critical or high alert, so the clinician still learns it happened. Advisory alerts resolve automatically when cleared **[assumption]**. | Must | A brief critical event that clears before anyone acknowledges it stays open until acknowledged. |

## 6. Live view and history

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-24 | The system shall show the latest vitals for every bed in a unit, updating live, to clinicians of that unit. | Must | The unit view reflects a new reading within 1 second **[assumption]**. |
| FR-25 | The system shall store every accepted reading and serve history by patient and time range. | Must | A query for a patient's last hour returns every stored reading in order. |
| FR-26 | For long ranges the system shall serve pre-computed roll-ups (one-minute and one-hour minimum, maximum, average, count) instead of raw readings. | Must | A 7-day chart query reads roll-ups, not raw rows. |
| FR-27 | The system shall serve the full history of alerts for a patient, with every lifecycle event. | Should | An alert timeline is returned for any admission. |

## 7. Configuration

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-28 | Clinical administrators shall be able to create and change unit threshold profiles, patient overrides, and escalation policies. | Must | A changed threshold takes effect on the detector within 5 seconds **[assumption]** without a restart. |
| FR-29 | Charge nurses shall be able to assign clinicians to beds for a shift. | Must | A new assignment changes routing for the next alert. |
| FR-30 | Every configuration change shall be versioned, with who changed it and when. | Should | The threshold profile in force at the time of any past alert can be retrieved. |

## 8. Access control and audit

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-31 | Access shall be role-based. Clinicians see only patients in their own unit; administrators configure but do not browse patient vitals; auditors read audit records only. | Must | A request for another unit's patient is refused and audited. |
| FR-32 | Every read of patient data and every alert action shall be recorded in an append-only audit log: who, which patient, what action, when, and from where. | Must | Each history query and each acknowledgement produces exactly one audit record. |
| FR-33 | Auditors shall be able to query the audit log by patient, by user, and by time range. | Should | An auditor can list everyone who viewed a given patient last week. |

## 9. Simulation and testability

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-34 | A bed simulator shall generate realistic vitals for a configurable number of beds and play scripted scenarios with known expected alerts: falling SpO2, rising heart rate, artefact bursts, monitor disconnect, transfer. | Must | Each scenario's expected alerts are written down, and a test compares them with the actual alerts. |
| FR-35 | The system shall include a canary bed that raises a known alert on a fixed schedule, so alert-path health and latency are measured all the time, not only during benchmarks. | Should | Canary alert latency and success rate appear on the operations dashboard. |
