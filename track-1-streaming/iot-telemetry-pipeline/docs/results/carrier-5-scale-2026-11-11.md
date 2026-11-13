# Scale and multi-region, local run, 2026-11-11

Part 5: the laptop load benchmark against 5,000 events/s (NFR-1), a projection to full scale with its cost, and losing a whole region (NFR-7, RTO < 60 s, no loss). Measured in a build container with 4 cores shared by every container, not on the 16GB laptop; rerun the steps below there.

## Summary

| Check | Target | Result |
|---|---|---|
| Sustained throughput, 10 min | 5,000 events/s, lag not growing | 4,986 events/s for 600 s, lag back to 0 after the start burst and flat from then on. **PASS** |
| No loss at that load | Every acknowledged byte counted once | 2,992,677 events, 57,725,907,874 bytes acknowledged and counted, 0 short, 0 over. **PASS** |
| Freshness at that load | p95 50 ms | p95 195 ms. **Not met** on 4 shared cores, see below |
| Whole region lost | RTO < 60 s, no loss | Traffic moved to the other region in under 1 s; both regions counted every acknowledged byte. **PASS** |

## Load benchmark (NFR-1)

The simulator's gateway mode sends 100 events per request, the way a carrier's event gateway would, instead of one request per line. 22,000 lines, an event every 4 s each, 600 s, 2 customer requests a second, webhooks on.

| | Result |
|---|---|
| Events generated and accepted | 2,992,677 (4,986/s) |
| Requests that failed and were resent | 37,639, all in the first 35 s while 22,000 lines sent their first events at once; every one was accepted on a resend |
| Processor lag (30 s samples) | 99,677 and 103,919 in the first minute, 38,198 at 90 s, then between 0 and 2,000 (under half a second of traffic) and 0 for the last 5 minutes |
| Requests | 1,113 accepted, 0 stuck `PENDING`. **PASS** |
| Usage | 22,000 lines, 0 short, 0 over. **PASS** |
| Notifications | 0 duplicates, 0 bad signatures, 0 out of order; customers 001 to 003 got theirs (4,857, 4,849, 4,823). **FAIL**, see below |

Latency over the last 389 s (after a 240 s warm-up), and at half the load:

| Hop | 5,000/s p50 | p95 | p99 | 2,500/s p95 | 100/s p95 (part 4) |
|---|---|---|---|---|---|
| Gateway request (100 events) | 20.3 ms | 55.6 ms | 86.8 ms | 31.9 ms | 6.1 ms (1 event) |
| Kafka produce, confirmed | 4.0 ms | 12.4 ms | 24.6 ms | 8.6 ms | 4.3 ms |
| Received to picked up by the processor | 50.4 ms | 134.5 ms | 203.6 ms | 106.8 ms | 7.7 ms |
| PostgreSQL transaction, per batch | 4.6 ms | 20.9 ms | 42.3 ms | 10.3 ms | 3.8 ms |
| Redis writes, per batch | 3.3 ms | 10.7 ms | 18.3 ms | 7.4 ms | 1.7 ms |
| **Freshness: received to readable** | **71.7 ms** | **195.4 ms** | **314.3 ms** | **149.5 ms** | **13.5 ms** |

**Why freshness misses 50 ms here.** The 4 cores were shared by Kafka, PostgreSQL, Redis, six services, the simulator, Prometheus and Grafana, and at 5,000 events/s they were all busy (about 3.5 cores used). Events wait in Kafka for a processor thread, so the queue hop grows with load. Giving the processor 12 threads instead of 3 made it worse (freshness p95 462 ms at 2,500/s): more threads competing for the same cores and the same database. On this hardware the throughput target is met and the latency target is not. The full-scale sizing below keeps every component at or under 50% CPU, where part 4 measured 13.5 ms. That figure at full scale is an expectation until it is measured on real nodes (part 6).

**Why notifications failed.** Two customers have endpoints that cannot keep up by design: customer-005's endpoint is dead (4,858 waiting), and customer-004's takes 2 s per call (3,217 waiting; four calls at a time is 2 a second against about 8 offered). The other three customers were unaffected: one slow customer does not delay the rest, as the problem statement asks. A customer with a 2 s endpoint at this rate needs batched webhooks or more calls in flight; that is a product decision, not something the benchmark can fix.

**Fixes this benchmark needed.**
- event-ingest was killed for running out of memory 18 times at 5,000/s: 233 threads and native memory pushed it past its 400 MB limit. Every service now caps thread stacks, direct buffers, code cache and metaspace (`JAVA_OPTS` in `docker-compose.yml`), Tomcat runs at most 64 threads, and event-ingest and state-processor get 512 MB.
- The notifier paused a customer's endpoint after any one failure, so the flaky endpoint (30% failures) was paused nearly all the time and fell behind. It now pauses only after 5 failures in a row.

Memory at 5,000/s: event-ingest 382 MB of 512, state-processor 375 of 512, PostgreSQL 318, Redis 207 of 256, Kafka 662 of 1,024.

## Losing a whole region (NFR-7)

`docker-compose.regions.yml` runs two complete regions, east and west, each with its own Kafka, PostgreSQL, Redis, customer-api, event-ingest and state-processor. MirrorMaker 2 copies each region's `network.events` to the other (as `east.network.events` in west and `west.network.events` in east), and each region's processor reads both, so both regions hold every line's state. Duplicates are dropped by event id (ADR-006), so an event that reaches a region twice is counted once. Both regions accept the same network key.

The simulator sends to east and moves to west after 20 requests in a row fail as a whole. The drill killed all six east containers with `SIGKILL` 60 s into a 180 s run and started them again 60 s later. 1,000 lines, 5 customers.

| Time (UTC) | What happened |
|---|---|
| 05:24:04 | The drill kills every east container |
| 05:24:04.65 | The simulator fails over to west |
| 05:24:07 to 05:24:17 | West accepts 2,670 and then 4,341 events per 5 s while the simulator resends what east never acknowledged (212 failed requests) |
| 05:25:07 | East starts again |
| 05:25:59 | East is healthy and MirrorMaker copies to it what west took meanwhile |

| Region | Lines | Matched | Short | Over | Bytes acknowledged | Bytes counted |
|---|---|---|---|---|---|---|
| East | 1,000 | 1,000 | 0 | 0 | 2,433,661,711 | 2,433,661,711. **PASS** |
| West | 1,000 | 1,000 | 0 | 0 | 2,433,661,711 | 2,433,661,711. **PASS** |

**RTO:** under 1 s from the kill to west accepting at full rate, against 60 s. West's processor never stopped, so processing did not need to resume.

**What this does not show.** Copying between regions is asynchronous. Events east acknowledged in the moment before it died, and had not copied yet, reached west only when east came back. In this run both regions were reconciled after east returned. If a region were lost for good, those events (MirrorMaker lag, normally milliseconds) would be lost with it unless devices kept them until both regions had them. RPO = 0 for a permanent region loss needs either that or synchronous writes to two regions, at the cost of cross-region latency on every event. Customer requests, webhooks and presence ran in one region only in this simulation.

The first attempt also found a simulator bug: requests still in flight to the dead region kept failing after the switch and sent it straight back, so it flapped between the two 25 times. Failures now count only against the region the simulator is using.

The two-region stack used about 3.2 GB, so run it on its own.

## Full-scale sizing and cost (NFR-1, NFR-13)

Measured CPU at 5,000 events/s, per 1,000 events/s: event-ingest 0.14 cores, state-processor 0.18, PostgreSQL 0.16, Redis 0.07, Kafka 0.04. Stored in Kafka, an event took about 350 bytes (lz4, with headers).

Projected to 50,000 events/s at peak, linearly from that, with each region able to carry the whole peak alone (so that losing one region loses no capacity) and every component at 50% CPU at peak:

| Per region | Cores at peak | Sized at 50% | Notes |
|---|---|---|---|
| event-ingest | 7 | 14 vCPU | e.g. 7 instances of 2 vCPU |
| state-processor | 9 | 18 vCPU | One thread handled about 1,700 events/s; 150,000/s headroom (NFR-13) needs about 90 threads, so 128 partitions |
| PostgreSQL | 8 | 16 vCPU, plus a 16 vCPU standby | |
| Redis | 3.5 | 12 vCPU | 3 shards with a replica each, 2 vCPU per node |
| Kafka | 2 | 12 vCPU | 3 brokers, sized for disk not CPU |
| MirrorMaker 2 | | 4 vCPU | Not measured under load |
| Other services | | 8 vCPU | customer-api, presence-monitor, provisioning-worker, notifier |
| **Total** | | **about 100 vCPU** | |

Storage per region: about 1.3 billion events a day at 350 bytes is 455 GB a day; 7 days kept with 3 replicas is about 9.6 TB of Kafka disk. PostgreSQL with 90 days of history, about 2 TB, doubled for the standby.

Monthly cost for two regions, using approximate AWS on-demand list prices (general-purpose instances at about $0.05 per vCPU-hour, gp3 disk at $0.08 per GB-month, $0.02 per GB between regions). These are assumptions to check against a current price list, not quotes:

| Item | Amount | Per month |
|---|---|---|
| Compute | 200 vCPU | about $7,400 |
| Kafka disk | 19.2 TB | about $1,550 |
| PostgreSQL disk | 8 TB | about $650 |
| Copying between regions | about 14 TB | about $280 |
| **Total** | | **about $9,800**, or about $7,200 with one-year commitments on compute |

That is about $0.002 per line per month at 5 million lines. Managed Kafka, PostgreSQL and Redis would cost more than self-run instances, and the projection is linear from 5,000 events/s on shared cores; part 6 measures several instances per service on Kubernetes.

## Reproduce

Load benchmark (about 4 GB with the observability overlay):

```
./mvnw -DskipTests package
docker compose down -v --remove-orphans
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
mkdir -p target/ledger
docker compose -f docker-compose.yml -f docker-compose.observability.yml --profile sim run --rm -e LINES=22000 -e TICK_SECONDS=4 -e DURATION=600s -e GATEWAY_BATCH=100 -e REQUEST_RATE=2 network-simulator
```

Two regions (stop the main stack first):

```
docker compose -f docker-compose.yml -f docker-compose.observability.yml down -v
docker compose -f docker-compose.regions.yml --profile sim build
docker compose -f docker-compose.regions.yml up -d --wait
docker compose -f docker-compose.regions.yml --profile sim run --rm -e MODE=register -e API_URL=http://customer-api-east:8084 network-simulator
docker compose -f docker-compose.regions.yml --profile sim run --rm -e MODE=register -e API_URL=http://customer-api-west:8084 network-simulator
docker compose -f docker-compose.regions.yml --profile drills run -d --name drill drill
docker compose -f docker-compose.regions.yml --profile sim run --rm -e DURATION=180s network-simulator
docker logs drill
docker compose -f docker-compose.regions.yml --profile sim run --rm -e MODE=reconcile -e API_URL=http://customer-api-east:8084 network-simulator
docker compose -f docker-compose.regions.yml --profile sim run --rm -e MODE=reconcile -e API_URL=http://customer-api-west:8084 network-simulator
docker rm drill
docker compose -f docker-compose.regions.yml down -v
```

Start the drill and then the traffic straight after; the drill waits 60 s before killing east. Wait for `docker logs drill` to say `healthy` before reconciling. Each reconcile line ends in `PASS` or `FAIL`.
