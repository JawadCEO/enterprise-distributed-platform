# Failure drills, local run, 2026-11-11

Part 3: kill one part of the stack in the middle of a simulator run, bring it back, and check that nothing was lost. Measured in a build container, not on the 16GB laptop; rerun the steps below there.

## Setup

- `docker-compose.drills.yml` runs three Kafka brokers instead of one. Every topic has three replicas and `min.insync.replicas=2`, so a write the platform acknowledged is on at least two brokers.
- The `drill` container (`drills/drill.sh`) kills one service with `SIGKILL` 60 s after it starts, keeps it down, starts it again and logs each step.
- Each drill runs the `faults` scenario: 1,000 lines, 5 customers, 3 regions, 180 s, about 3 customer requests a second, with the five webhook endpoints (ok, flaky, late, slow, dead).
- The run passes only if all three reconciliations pass: every request ended the way the network decided, every acknowledged byte of usage was counted exactly once, and every notification was delivered once, signed and in order.
- The stack used about 3.2 GB with three brokers, plus 512 MB for the simulator.
- `./mvnw test`: 42 tests, 0 failures.

## Results

| Killed | Down | What happened | Requests | Usage | Webhooks |
|---|---|---|---|---|---|
| One Kafka broker (`kafka-2`) | 30 s | Ingest paused for about 10 s while partition leaders moved, then ran at full rate on two brokers | PASS | PASS, 292,670,218 bytes | PASS |
| PostgreSQL | 30 s | Ingest kept accepting about 90 events/s from its caches; processing paused and caught up from Kafka once the database was back | PASS | PASS, 297,234,499 bytes | PASS |
| Redis | 30 s | Ingest kept accepting; each call waited for the 200 ms Redis timeout and used the local rate limit. Duplicate checks fell back to the database key | PASS | PASS, 297,511,142 bytes | PASS |
| `state-processor` | 20 s | Ingest unaffected; events waited in Kafka and were processed after the restart, 12 s after it started | PASS | PASS, 289,642,208 bytes | PASS |
| `event-ingest` | 20 s | The only instance, so ingest was down for about 30 s (20 s down, 11 s to start). The simulator kept every unacknowledged event and resent it | PASS | PASS, 295,068,098 bytes | PASS |
| `notifier` | 20 s | Notifications waited in PostgreSQL and were sent after the restart | PASS | PASS, 291,132,955 bytes | PASS |
| `customer-api` | 20 s | Customer requests failed while it was down; nothing already accepted was lost | PASS | PASS, 279,416,009 bytes | PASS |

Each drill had 0 short and 0 over in usage, 0 requests stuck `PENDING`, and 0 webhooks missing, unknown, badly signed or out of order.

## What the drills found, and the fixes

**Ingest stopped while the database was down.** event-ingest caches lines and API keys for 30 s. When an entry expired it had to be read again, and with the database down the call waited up to 30 s for a connection. The first database drill took 0 events for the 30 s the database was down, and 900 simulator calls timed out.
- Fixed: the caches now reload in the background, and a reload that fails keeps the old entry for up to an hour. Lines and keys already seen keep working; a removed key or deactivated line still takes effect within about 30 s while the database is up.
- A line not cached yet now gets `503` with `Retry-After: 1` within 1 s instead of hanging.
- Rerun: ingest kept accepting at its usual rate through the whole outage.

**Redis forgot which lines to watch.** Each line's offline deadline lives only in Redis. After a Redis restart, a line that had gone quiet before its next event would never be marked offline.
- Fixed: every 2 minutes presence-monitor finds lines PostgreSQL still has online but silent past their window, and puts back any missing deadline (`ZADD NX`, so it never moves one the processor set). After emptying Redis by hand it put back 944.

**That repair then marked a quiet fleet offline.** The "every region went quiet at once" latch, which pauses offline marking, was also kept only in Redis. A Redis restart while the whole fleet was quiet forgot it, and the restored deadlines then marked 944 lines offline with an alert each.
- Fixed: the latch is now kept in PostgreSQL (`presence_latch`, migration V6).
- Rerun: Redis restarted during a fleet-wide silence, 945 deadlines were put back, and no line was marked offline.

## Notes

- **Webhook latency during a drill.** Notifications wait while the database or the notifier is down and go out once it is back. In the database drill, p95 event-to-webhook was 13.6 s against a usual 30 ms.
- **Duplicate deliveries.** Two notifications were received twice after the database was killed. Delivery is at least once and each repeat carries the same `X-Notification-Id`, so a customer can drop it.
- **One instance per service.** Each service runs one instance here, so these drills measure restart time, not another instance taking over its partitions. That needs two or more instances behind a load balancer (part 6, Kubernetes).
- **Recovery times.** A broker took about 10 s to recover and a service about 11 to 12 s from start to healthy, inside the 30 s target for a node (NFR-7).
- **Customer requests while ingest is down.** The network's provisioning results come back through event-ingest. While it was down they waited, so in that drill the p95 from request to `COMPLETED` was 22.5 s.
- **Not drilled yet.** A whole region (part 5) and network partitions between services.

## Reproduce

Each step is one command. With three brokers, every `docker compose` command needs both files:

```
./mvnw -DskipTests package
docker compose down -v --remove-orphans
docker compose -f docker-compose.yml -f docker-compose.drills.yml up -d --build
mkdir -p target/ledger
docker compose -f docker-compose.yml -f docker-compose.drills.yml --profile drills run -d --rm --name drill -e TARGET=kafka-2 -e DRILL_AT=60 -e DRILL_FOR=30 drill
docker compose -f docker-compose.yml -f docker-compose.drills.yml --profile sim run --rm -e SCENARIO=faults -e LINES=1000 -e DURATION=180s -e REQUEST_RATE=3 network-simulator
```

Start the drill and then the simulator straight after; the drill waits 60 s before the kill. For the other drills, change `TARGET` to `postgres`, `redis`, `state-processor`, `event-ingest`, `notifier` or `customer-api`. `docker logs drill` shows when the target went down and came back. Each run ends with `Request reconciliation`, `Usage reconciliation` and `Notification reconciliation` lines, each saying `PASS` or `FAIL`.
