# Customer requests and provisioning, local run, 2026-11-11

First end-to-end run of part 2b: customer line requests (activate, suspend, restore, deactivate) through `customer-api`, carried out by the new `provisioning-worker` against a simulated carrier network, and confirmed by `state-processor` when the network's `PROVISIONING_RESULT` comes back. Measured in a build container, not on the 16GB laptop; rerun the steps below there.

## Unit tests

`./mvnw test`: 31 tests, 0 failures.

## Run

`faults` scenario, 1,000 lines across 5 customers, 120 s, about 3 customer requests a second, on a fresh database. The simulated network answers in about 1 s, rejects 5% of commands, refuses 5% of calls with `503` (the worker retries), and never answers 1% (the time limit has to fail those).

| Check | Result |
|---|---|
| Requests accepted | 340 |
| Outcomes | 320 `COMPLETED`, 16 `FAILED` with `NETWORK_REJECTED`, 4 `FAILED` with `NETWORK_TIMEOUT`, 0 still `PENDING` |
| Outcomes match the network | 340 of 340: every completed request was `DONE` on the network, every rejected one `REJECTED`, every timed-out one never answered |
| Lines left `PENDING` | 0 |
| Same `Idempotency-Key` sent twice | 20 of 20 got the original `requestId` back; the line changed once |
| Second request while one is in progress | 7 of 7 got `409 REQUEST_IN_PROGRESS` |
| Action the line's state doesn't allow | 6 of 6 got `409 INVALID_STATE` |
| `202` to `COMPLETED` (NFR-2b, target p95 < 5 s with a 1 s network) | p50 1,058 ms, p95 1,501 ms |
| Outboxes | 0 commands and 0 line changes left unpublished |
| Usage (part 2a check, still on) | 994 lines matched exactly, 187,600,044 bytes; 6 deactivated lines skipped (see below) |

## Validation order (FR-5), checked by hand

| Call | Answer |
|---|---|
| No `Idempotency-Key` | `400 IDEMPOTENCY_KEY_REQUIRED` |
| Another customer's line | `404` |
| Over 20 requests a second | `429` with `Retry-After` (10 of 30 quick calls) |
| `restore` on an `ACTIVE` line | `409 INVALID_STATE` naming the state |
| Same key again | `202` with the original `requestId` |
| `deactivate` while a `suspend` is pending | `409 REQUEST_IN_PROGRESS` naming the pending request |
| Account `BLOCKED` | `403 ACCOUNT_BLOCKED` |

## Worker kill drill (FR-8)

`provisioning-worker` stopped, 5 suspend requests sent (all `202`, all `PENDING`), worker started again. The commands waited in `line.commands`; all 5 finished about 11 s after they were sent: 4 `COMPLETED`, 1 `FAILED` because the simulated network rejected it.

## Notes

- A request's history is in `GET /v1/lines/{iccid}/history`: `REQUEST_ACCEPTED`, `LINE_STATE_CHANGED` to and from `PENDING`, and `REQUEST_COMPLETED` or `REQUEST_FAILED`. The last two are marked to notify the customer; webhooks come in part 2c.
- Usage on a line that was deactivated during the run is not compared: event-ingest can still accept events for it until its registry cache refreshes (30 s), and state-processor then dead-letters them as `INACTIVE_LINE` instead of billing them.
- A result that arrives after the sweeper already timed the request out is ignored and logged. The line stays where the timeout put it. Reconciling those with the network is left for later.
- Bulk jobs (FR-9) and profile switches (FR-10) are not built yet.

## Reproduce

```
./mvnw -DskipTests package
docker compose down -v --remove-orphans
docker compose up -d --build
mkdir -p target/ledger
SCENARIO=faults LINES=1000 DURATION=120s REQUEST_RATE=3 docker compose --profile sim run --rm network-simulator
```

The run ends with a `Request reconciliation` line and a `Usage reconciliation` line, each saying `PASS` or `FAIL`. The simulator exits with 1 if either fails.
