# Lines and network events, local run, 2026-11-10

First end-to-end run of part 2a (`docker compose up -d --build`): one Kafka broker, PostgreSQL, Redis and four services (`event-ingest`, `state-processor`, `presence-monitor`, `customer-api`), driven by `network-simulator`. Measured in a build container, not on the 16GB laptop; rerun the steps below there for laptop numbers.

The earlier [device run](local-run-2026-11-10.md) covers the code before the carrier rename.

## Unit tests

`./mvnw test`: 27 tests, 0 failures.

## Run

`faults` scenario, 1,000 lines across 5 customers, 10 s ticks, 120 s, on a fresh database (`docker compose down -v` first).

| Check | Result |
|---|---|
| Events | 11,554 generated, 11,466 acknowledged, about 96 events/s |
| Rejected at ingest, by code | 570 `INVALID_PAYLOAD_FIELD` (injected), 112 `UNKNOWN_LINE` (injected), 88 `TIME_IN_FUTURE` (device clocks pushed 2 days ahead on purpose) |
| Usage counted exactly once | 1,565 usage records, 202,540,967 bytes acknowledged; customer-api reports the same 202,540,967 bytes. 1,000 of 1,000 lines match, 0 short, 0 over, with 1,024 duplicate events resent on purpose |
| Ingest acknowledgement latency | p50 5.4 ms, p95 44.8 ms, p99 1,165 ms (the p99 is the first seconds, while the JVMs warm up) |
| SIM moves | 23 IMEI changes gave 23 `SIM_MOVED` changes and 23 security events: 17 MEDIUM on removable SIMs, 6 HIGH on embedded SIMs |
| Auto-suspend | The one embedded SIM that moved in `customer-001` (the customer that opted in) went `ACTIVE` to `SUSPENDED` with reason `SIM_MOVED`. The 5 in other customers stayed `ACTIVE` |
| Attach, detach, offline | 63 detaches marked lines `OFFLINE` with reason `DETACHED`; lines that went quiet were marked `OFFLINE` by presence-monitor |
| Outbox | 3,017 line changes written, 0 left unpublished on `line.changes` |
| Customer isolation | A customer key reading another customer's line gets 404; a customer key on `/v1/dead-letters` gets 403 |
| Dead letters | A malformed record written straight to `network.events` was stored as `MALFORMED_RECORD` within seconds and replayed with `POST /v1/dead-letters/{id}/replay` |

## Fixes needed to get it running

| Problem | Fix |
|---|---|
| Every line in `customer-001` got a removable SIM, because SIM type and customer both came from the line number, so auto-suspend could never trigger | SIM type comes from the line's position within its customer |
| The ledger folder mounted from the host was not writable by the container user on Linux | `chmod 777 target/ledger` on Linux hosts; Docker Desktop on Windows is not affected |

## Not covered yet

- Customer requests, provisioning, webhooks and the outage guard: parts 2b and 2c.
- Broker, database and instance kill drills: part 3.
- Throughput above about 100 events/s.

## Reproduce

```
./mvnw -DskipTests package
docker compose down -v
docker compose up -d --build
mkdir -p target/ledger
SCENARIO=faults LINES=1000 DURATION=120s docker compose --profile sim run --rm network-simulator
```

The simulator ends with a `Usage reconciliation` line that says `PASS` or `FAIL`, and exits with 1 on `FAIL`.
