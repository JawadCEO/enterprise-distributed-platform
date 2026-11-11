# Webhooks and the outage guard, local run, 2026-11-11

First end-to-end runs of part 2c: customers register webhook endpoints in `customer-api`, the new `notifier` delivers signed notifications to them, and `presence-monitor` sends one outage notice per customer when a whole region goes silent instead of one alert per line. Measured in a build container, not on the 16GB laptop; rerun the steps below there.

## Unit tests

`./mvnw test`: 42 tests, 0 failures.

## How the simulator checks webhooks

Each simulated customer registers one endpoint served by the simulator, subscribed to every type. The five endpoints behave differently on purpose:

| Customer | Endpoint |
|---|---|
| customer-001 | ok: answers at once |
| customer-002 | flaky: fails 30% of requests with `503` |
| customer-003 | late: down for the first 60 s, then fine |
| customer-004 | slow: takes 2 s to answer |
| customer-005 | dead: always `503` |

Every endpoint checks the signature and timestamp the way a customer should. At the end the simulator compares three things: what `customer-api` says was sent, what the endpoints accepted, and what the simulator itself caused (its requests, usage and outage). The run ends with a `Notification reconciliation` line saying `PASS` or `FAIL`.

## Run 1: faults

`faults` scenario, 1,000 lines across 5 customers and 3 regions, 120 s, about 3 customer requests a second.

| Check | Result |
|---|---|
| Notifications | 380: 300 delivered, 80 pending (all for the dead endpoint) |
| Delivered by the platform but never received | 0 |
| Received but unknown to the platform | 0 |
| Left pending for a working endpoint | 0, including the flaky, late and slow ones |
| Delivered to the dead endpoint | 0 |
| Bad signatures | 0 |
| Out of order for a line | 0 |
| Requests | 305 finished, each with exactly one `REQUEST_COMPLETED` or `REQUEST_FAILED` of the right type |
| Event to webhook, healthy endpoint (NFR-2a, target p95 < 2 s) | p50 16 ms, p95 32 ms, while the other four endpoints were failing or slow |
| Requests and usage (2a and 2b checks, still on) | Both `PASS` |

On a fresh database the same scenario also crossed usage thresholds: 34 `USAGE_80` and `USAGE_100` notifications, each exactly once per line where the run crossed it (FR-25), with 1,000 lines checked.

## Run 2: regional outage

`outage` scenario, same fleet, 300 s. Every line in region `south` goes silent 150 s in, for 90 s, then all of them reconnect at once.

| Check | Result |
|---|---|
| Outage detected | 32 s after the region went silent (0 events/s against a usual 30.7) |
| Outage notices | Each of the 5 customers got exactly one `OUTAGE_STARTED` and one `OUTAGE_ENDED` |
| Lines marked offline during the outage | 219, with reason `REGION_OUTAGE` and no alert |
| Offline or online alerts for those lines | 0, during the outage and after they reconnected |
| Outage ended | 33 s after the lines came back |
| Requests, usage and every webhook check | All `PASS` |

## Checked by hand

| Call | Answer |
|---|---|
| `POST /v1/webhooks` with an `ftp://` URL | `400 INVALID_URL` |
| `POST /v1/webhooks` with an unknown type | `400 INVALID_TYPES` |
| `POST /v1/webhooks` | `201` with the secret, shown only here |
| `GET /v1/webhooks/{id}` | `200` without the secret |
| Another customer's webhook or notification | `404` |
| No API key | `401` |
| Notifier restarted with `NOTIFY_RETRY_FOR=60s` | Every pending notification for the dead endpoint was parked within one sweep |
| `POST /v1/notifications/{id}/redeliver` on a parked one | `202`, back to `PENDING` with a fresh retry period |
| The same call again | `409 NOT_PARKED` |
| `DELETE /v1/webhooks/{id}` | `204`; the endpoint is kept inactive and its pending notifications are parked, so they stay readable |

## How it works

- `notifier` reads `line.changes` and `customer.notices`. For each change marked for the customer it stores one notification per subscribed endpoint. The notification ID comes from the change and the endpoint, so a change read twice is stored once and keeps the same ID on every attempt.
- Delivery is asynchronous, at most 4 requests in flight per endpoint, with a 5 s timeout. A failure is retried with exponential back-off (1 s doubling to 10 min, with jitter). An endpoint that keeps failing is paused as a whole, so a dead one costs one probe per back-off step. After 24 h a notification is parked.
- A notification is sent only when no earlier one for the same line and endpoint is still pending, which keeps per-line order.
- `state-processor` counts events per region in 10 s buckets in Redis. `presence-monitor` compares each region's last 20 s with its rate over the 2 min before. Below 20% of usual, the region is in an outage. Above 60%, the outage ends.
- Offline marking waits 45 s past a line's deadline, so the guard has seen the region first. A line that went offline in an outage is not announced when it comes back.
- If every region goes quiet at once, that is our side or the whole network, not one region. No outage is declared and no line is marked offline until traffic returns. This also keeps the end of a simulator run from producing a flood of offline alerts.

## Notes

- The guard's thresholds are first guesses sized for the simulator's 20 s keep-alive windows (HLD open item 7, tuned in phase 6). Real windows are 5 to 60 minutes, which leaves far more margin.
- A line still offline when an outage ends is counted in that customer's `OUTAGE_ENDED` notice (`linesStillOffline`), not alerted on its own.
- A customer's first-ever "device online" is still sent, so importing a fleet sends one per line.
- Bulk job summaries (FR-23) wait for bulk jobs (FR-9).
- Outside this laptop stack, webhook URLs would also need to be `https` and must not point at private addresses.

## Reproduce

```
./mvnw -DskipTests package
docker compose down -v --remove-orphans
docker compose up -d --build
mkdir -p target/ledger
SCENARIO=faults LINES=1000 DURATION=120s REQUEST_RATE=3 docker compose --profile sim run --rm network-simulator
SCENARIO=outage LINES=1000 DURATION=300s docker compose --profile sim run --rm network-simulator
```

Each run ends with `Request reconciliation`, `Usage reconciliation` and `Notification reconciliation` lines, each saying `PASS` or `FAIL`. The simulator exits with 1 if any of them fails.
