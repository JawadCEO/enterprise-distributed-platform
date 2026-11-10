# iot-telemetry-pipeline: Functional Requirements

**Status:** draft for review. Items marked **[assumption, Qn]** follow the recommended answer to open question n in [open-questions.md](open-questions.md). Items marked **[assumption]** are sizing choices to confirm in the low-level design. Flows A to E are described in [how-it-works.md](how-it-works.md).

Priority: **P0** is needed for the core story, **P1** for a complete project, **P2** only if time allows.

## Lines, SIMs and customers

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-1 | Keep a registry of customers, lines and devices. Each line has an ICCID, a customer, a SIM type (`REMOVABLE`, `EMBEDDED`, `INTEGRATED`), a plan with a data allowance, a keep-alive window, and the IMEI of the device it was last seen in | P0 | Lines can be created in `INVENTORY`, looked up by ICCID, and listed per customer |
| FR-2 | Keep a line state per line: `INVENTORY`, `PENDING`, `ACTIVE`, `SUSPENDED`, `DEACTIVATED`, changing only by the allowed transitions | P0 | The transition table in [data.md](data.md#5-state-models) is enforced; an attempt to break it is rejected and logged |
| FR-3 | Manage customers and their rate limits **[assumption, Q1]** | P1 | A change to a customer's limit takes effect at the gateways within 60 s |

## Customer requests (Flow A, Flow E)

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-4 | Accept line requests: activate, suspend, restore (reconnect), deactivate | P0 | `POST /v1/lines/{iccid}/{action}` returns `202` with a `requestId` once the request is durable in Kafka |
| FR-5 | Validate every request in a fixed order and stop at the first failure: credentials, line ownership, rate limit, idempotency key, line state allows the action, no other request in progress on the line, account in good standing | P0 | Each failure returns its own status and code (`401`, `404`, `429`, `409 INVALID_STATE`, `409 REQUEST_IN_PROGRESS`, `403 ACCOUNT_BLOCKED`), tested one by one |
| FR-6 | Make requests idempotent by `Idempotency-Key` | P0 | Repeating a request with the same key within 24 h returns the original `requestId` and status; the line changes once |
| FR-7 | Carry out each request against the network with retries and a time limit | P0 | A network failure is retried with back-off; with no answer in 2 minutes **[assumption]** the request ends `FAILED` with `NETWORK_TIMEOUT` and the line returns to its previous state |
| FR-8 | Every accepted request ends `COMPLETED` or `FAILED` with a reason | P0 | No request stays `PENDING` past its time limit, including after a worker is killed mid-request |
| FR-9 | Accept bulk requests (up to 50,000 lines in one job) and pace them toward the network | P1 | A bulk job is split into one command per line; the network never receives more than its configured rate; the job reports succeeded and failed counts |
| FR-10 | Remote SIM profile download and switch, for embedded and integrated SIMs only (Flow E) | P2 | A removable line gets `409 NOT_SUPPORTED_FOR_SIM_TYPE`; a failed switch falls back to the old profile and the request fails with the reason |

## Network and device events (Flows B, C, D)

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-11 | Accept events from the network and devices: `DEVICE_ATTACHED`, `DEVICE_DETACHED`, `DEVICE_STATUS`, `KEEP_ALIVE`, `USAGE_RECORD`, `IMEI_CHANGED`, `PROVISIONING_RESULT`, singly or in batches | P0 | A batch of up to 100 events or 256 KB **[assumption]** gets a result per event: accepted, rejected with a reason, or rate limited |
| FR-12 | Acknowledge an event only after it is durable in Kafka | P0 | A 2xx means at least 2 of 3 replicas confirmed it; otherwise `503` and the sender resends |
| FR-13 | Reject malformed events and events for unknown or deactivated lines at the gateway, with a reason | P0 | They never reach Kafka; the sender gets `UNKNOWN_LINE` or `INACTIVE_LINE` per event, not a silent dead letter |
| FR-14 | Apply events for one line in the order the device produced them, including across reboots | P0 | Ordering uses `(bootId, sequence)`: a higher `bootId` wins even with a lower sequence; an older event never overwrites newer state |
| FR-15 | Drop duplicate events | P0 | Sending the same `eventId` N times has the same effect as sending it once |
| FR-16 | Keep each line's current device state: connection (`ONLINE`/`OFFLINE`), last seen, battery, signal, firmware, fault, current IMEI | P0 | Exactly one current-state row per line, updated only by a newer event |
| FR-17 | Count usage exactly once per line per billing cycle | P0 | After failure drills, every line's total equals the simulator's ledger to the byte |
| FR-18 | Detect a SIM appearing in a different device, with severity by SIM type | P1 | Removable: `SIM_MOVED` at medium severity. Embedded or integrated: high severity, and the line is suspended automatically if the customer opted in |

## Presence (Flow B)

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-19 | Mark a line offline on `DEVICE_DETACHED`, or when no event arrives within its keep-alive window plus 30 s grace **[assumption, Q2]** | P0 | The line goes `OFFLINE` with reason `DETACHED` or `KEEP_ALIVE_MISSED`; its next event brings it back `ONLINE` |
| FR-20 | Don't alert on expected silence | P0 | A line that is `SUSPENDED`, `DEACTIVATED`, or mid profile switch goes offline without a customer notification |
| FR-21 | Outage guard: when a large share of lines in a region go silent together, send one outage notice per customer instead of one alert per line | P1 | A simulated region outage of 500,000 lines produces at most one outage notice per affected customer, and one recovery notice |

## Notifications (all flows)

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-22 | Let customers register webhook endpoints and choose which notifications they want | P0 | Only subscribed types are sent, to the customer's own endpoints |
| FR-23 | Send notifications: request completed or failed, device offline or online, usage at 80% and 100%, SIM moved, outage and recovery, bulk job summary | P0 | Each is sent once per real change, signed so the customer can verify it came from us |
| FR-24 | Retry failed deliveries without blocking other customers, then park undeliverable ones | P0 | Back-off retries for up to 24 h **[assumption]**; a customer whose endpoint is down doesn't delay anyone else's notifications; parked ones stay readable by API for 30 days |
| FR-25 | Send each usage threshold once per billing cycle | P0 | Crossing 80% twice in one cycle (e.g. after a replay) sends one notification |

## Queries

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-26 | Current state of a line | P0 | Line state, device state and usage this cycle, from cache with a database fallback |
| FR-27 | History of a line: state changes, attach/detach, requests | P0 | By time range, paginated, for 90 days |
| FR-28 | List lines by state, connection or SIM type | P1 | Filtered and paginated, for the caller's own lines only |
| FR-29 | Status of a request or bulk job | P0 | For customers who still prefer to check |
| FR-30 | Every query and request is limited to the caller's own lines | P0 | A valid ICCID belonging to another customer returns `404` |

## Failure handling

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-31 | Retry events that fail for temporary reasons without blocking events behind them | P0 | A database timeout sends the event through retry tiers (5 s, 1 m, 10 m) while the partition keeps flowing |
| FR-32 | Move events that can never be processed to a dead-letter store with the reason | P0 | Stored with reason, attempt count, first and last failure times |
| FR-33 | Inspect and replay dead letters | P1 | Operators list by reason, customer or time, and replay one or a filtered set; replays are idempotent |

## Out of scope

Billing, analytics, application commands, firmware updates, value-based alerting, a user interface. See [problem-statement.md](problem-statement.md#6-non-goals).
