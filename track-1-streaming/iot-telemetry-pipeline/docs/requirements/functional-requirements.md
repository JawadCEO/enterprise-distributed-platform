# iot-telemetry-pipeline: Functional Requirements

**Status:** draft for discussion. Items marked **[assumption, Qn]** follow the recommended answer to open question n in [open-questions.md](open-questions.md). Items marked **[assumption]** are sizing choices to confirm in the low-level design.

Priority: **P0** is needed for the core story, **P1** is needed for a complete project, and **P2** is only if time allows.

## Ingestion

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-1 | Accept telemetry, status, and heartbeat events from authenticated devices, singly or in batches | P0 | A batch of up to 100 events or 256 KB **[assumption]** is accepted. The response gives a result for each event: accepted, rejected with a reason, or rate limited. |
| FR-2 | Acknowledge an event only after it is durably stored in the log | P0 | A 2xx response means the event is in `telemetry.raw` and at least 2 of 3 replicas have confirmed it. If Kafka does not confirm in time, the gateway returns 503 and the device resends. |
| FR-3 | Validate each event's shape; reject malformed events with a reason | P0 | An event with missing fields, wrong types, or a bad timestamp is rejected at the gateway with a machine-readable code and never reaches Kafka |
| FR-9 | Rate limit per device and per tenant **[assumption, Q1]**, returning a clear "slow down" response | P0 | An event over the limit gets 429 with `Retry-After`. Other devices and tenants are unaffected. |

## Processing

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-4 | Apply events for one device in the order the device produced them | P0 | Status reflects the event with the highest sequence number, whatever order events arrive in |
| FR-5 | Detect and discard duplicate events | P0 | Sending the same event (same device ID and sequence) N times leaves one telemetry row and the same status as sending it once |
| FR-6 | Maintain the latest status of every device | P0 | There is exactly one status row per registered device, and only a higher sequence number updates it |
| FR-7 | Store telemetry history for a defined retention period | P0 | Raw telemetry is kept for 48 hours and status changes for 90 days **[assumption, Q3]**. Older partitions are dropped automatically. |
| FR-15 | Publish a status-change event whenever a device's status changes | P1 | Each real change produces one event on `device.status-changed`. Repeats and stale events produce none. |

## Presence

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-13 | Mark a device offline when no event arrives within its heartbeat window **[assumption, Q2]** | P0 | A device silent past its window plus a 30s grace period goes offline and a status change is published. Its next event brings it back online. |

## Failure handling

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-10 | Retry events that fail for temporary reasons, without blocking the events behind them | P0 | A database timeout sends the event through retry tiers (5s, 1m, 10m) while the partition keeps flowing |
| FR-11 | Move events that cannot be processed to a dead-letter store with the failure reason | P0 | Permanent failures (unknown device, invalid payload) and events that ran out of retries are stored with the reason, attempt count, and first and last failure times |
| FR-12 | Allow dead-lettered events to be inspected and replayed | P1 | Operators can list dead-letter records by reason, tenant, or time, and replay one record or a filtered set. A replayed event is processed idempotently. |

## Queries and registry

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-8 | Serve read queries: current status of a device, recent history of a device, devices by status | P0 | Current status by device ID; history by device and time range, paginated; devices filtered by tenant and status, paginated |
| FR-14 | Register, update, and deactivate devices | P0 | Events from unregistered or deactivated devices are dead-lettered with reason `UNKNOWN_DEVICE` or `INACTIVE_DEVICE` |
| FR-16 | Manage tenants and their rate limits **[assumption, Q1]** | P1 | A change to a tenant's limit takes effect at the gateway within 60s |
| FR-17 | Restrict every query to the caller's own tenant | P1 | A caller cannot read another tenant's devices, even with a valid device ID |

## Out of scope

Commands to devices, firmware updates, alerting on metric values, analytics, and a user interface. See [problem-statement.md](problem-statement.md#6-non-goals).
