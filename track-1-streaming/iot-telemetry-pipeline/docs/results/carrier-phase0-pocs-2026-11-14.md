# Phase 0 PoCs: polling vs push, HTTP vs MQTT, 2026-11-14

The two tests [question 8](../requirements/open-questions.md) asked for before Phase 0 closed, run late against the finished platform rather than a throwaway prototype. Measured in a build container on the local stack: one instance of each service, 4 shared cores, with the load generator on the same machine.

## Answers

- **Polling vs push.** Push tells a customer about a device going online or offline in 20 ms (p95 32 ms). Polling every 30 s took 11.7 s at p50 and 27.6 s at p95, and missed 2 of 34 changes completely, because the device changed back before the next poll. Polling 5 million lines every 5 minutes would mean about 16,700 requests a second, on the order of 100 cores for customer-api alone, to learn things 2.5 minutes late on average. Push stays the design.
- **HTTP vs MQTT.** Both carry 2,000 events/s on one event-ingest instance with nothing lost. HTTP with one event per request is the costliest (96% of a core, p95 1.2 s). Batched HTTP (100 events per request) costs 38% of a core. MQTT costs 42% plus 10% for the broker, and gives devices the fastest acknowledgement (p95 3 ms). When 10% of devices dropped at once and came back together, neither lost an event. ADR-003 stands: HTTP first, with batching. The MQTT path now exists in event-ingest, off by default, for fleets whose devices speak MQTT.

## How the tests ran

`docker-compose.pocs.yml` adds a Mosquitto 2.0.20 broker and turns on event-ingest's MQTT path next to its HTTP endpoint. The usage comment at the top of that file has the exact commands.

**MQTT path in event-ingest** (`event-ingest/.../mqtt/MqttIngest.java`). It joins the shared subscription `$share/event-ingest/iot/events/+`, so several instances split the load. Devices publish at QoS 1 to `iot/events/{lineId}`. event-ingest acknowledges a message to the broker only after Kafka has accepted the event or rejected it for good; a rate-limited or failed batch is retried after 200 ms, so the broker keeps the message until then. The session is persistent (`cleanSession=false`, one client id per instance), so messages that arrive while an instance restarts wait at the broker. A message whose topic line differs from the line in the body is rejected. It is off unless `MQTT_ENABLED=true`.

**Load tool** (`network-simulator`, `MODE=bench`, `IngestBench.java`). 500 devices send `DEVICE_STATUS` events at a fixed total rate in 10 ms ticks, over one of:

- `http`: one `POST` per event;
- `http-batch`: 100 events per `POST`;
- `mqtt`: one MQTT client per device, QoS 1, persistent session.

At `BENCH_DISCONNECT_AT` it cuts 10% of the devices off at once, they keep generating events into a local buffer, and after `BENCH_RECONNECT_AFTER` (5 s) they reconnect together and send what they held. At the end it compares what devices saw acknowledged with event-ingest's `iot_ingest_events_total{result="accepted"}` counter, the count of events Kafka accepted.

**Polling probe** (`PollProbe.java`, `POLL_SECONDS=30`). During a normal simulator run with faults, it polls `GET /v1/lines/{iccid}` for each of the 1,000 lines once every 30 s, spread evenly. At the end it matches each `DEVICE_ONLINE` and `DEVICE_OFFLINE` webhook against the first poll that showed the change.

## HTTP vs MQTT: 2,000 events/s for 120 s, 500 devices

| | http | http-batch | mqtt |
|---|---|---|---|
| Sent | 240,000 | 240,000 | 240,000 |
| Acknowledged to devices | 234,197 | 239,886 | 240,000 |
| Refused (rate limited) | 5,803 | 114 | 0 (retried by event-ingest) |
| Accepted into Kafka | 234,197 | 239,886 | 240,000 |
| Device ack p50 / p95 / p99 | 23 / 1,247 / 1,908 ms | 9 / 18 / 28 ms | 1 / 3 / 12 ms |
| event-ingest CPU (one core = 100%) | 96.1% | 37.7% | 42.3% |
| Broker CPU | | | 9.5% |
| Events/s per core, rough | about 2,100 | about 5,300 | about 3,900 with the broker |

500 MQTT devices connected in 1.25 s. A trial at 1,000 events/s over MQTT used 36.4% plus 5.4% for the broker and Kafka accepted all 90,000, which shows most of event-ingest's CPU at this size is fixed cost, so the per-core figures are rough and favour the higher rate.

Every refusal was the per-customer rate limit, which was set to 400 events/s per customer, exactly the test's rate, so bursts tripped it. The load tool does not resend refused HTTP events; a real device would. Over MQTT the same refusals stay inside the platform: event-ingest holds the acknowledgement and retries (13,846 retries across the MQTT runs), and the device never sees them.

## 10% of devices disconnect at once

90 s at 2,000 events/s; at 40 s, 50 of the 500 devices are cut off, and 5 s later they come back together and send the 1,000 events they held.

| | http-batch | mqtt |
|---|---|---|
| Back and sending again | 2 ms | 400 ms (50 MQTT reconnects) |
| Held events sent on return | 1,000 | 1,000 |
| Sent / acknowledged | 180,000 / 178,915 | 180,000 / 180,000 |
| Refused (rate limited) | 1,085, not resent by the tool | 0 |
| Ack p95 during the drop and 10 s after | 23 ms | 42 ms |
| Lost | 0 | 0 |

Over MQTT, Kafka had accepted 173,560 events when the tool stopped waiting; the rest were still in event-ingest's retry loop because the reconnect burst hit the rate limit. The MQTT counter later showed every event accepted. Nothing was lost on either; MQTT turned the burst into a short delay, HTTP into refusals the device must resend.

## Polling vs push: 1,000 lines, 300 s with faults, polled every 30 s

| | Push (webhooks) | Polling every 30 s |
|---|---|---|
| Requests | 0 from the customer | 10,001 (22/s, 0 errors) |
| Online/offline changes noticed | 34 of 34 | 32 of 34 |
| Time to notice, p50 | 20 ms | 11,665 ms |
| Time to notice, p95 | 32 ms | 27,573 ms |

customer-api averaged 13.1% of a core while polling, which also covers the run's other API traffic, so about 6 ms of CPU per poll is an upper bound. The run's reconciliations all passed: requests (558 checked, 0 wrong), usage (506,341,095 bytes, matched exactly) and notifications (1,063 received, 0 lost, 0 duplicated).

**Projected to full scale.** The legacy poller asks about 5 million lines every 5 minutes: 16,700 requests/s. At up to 6 ms each, that is up to about 100 cores for customer-api, plus the database and cache reads behind it, and a change still takes 2.5 minutes on average to notice; any change undone within the interval is never seen. Push sends one webhook per change, and changes are a small fraction of lines per minute.

## Caveats

- Services, broker, database and load generator share 4 cores, so the numbers compare the options with each other; they are not capacity figures. [Part 5](carrier-5-scale-2026-11-11.md) has the capacity projection.
- An MQTT device's acknowledgement means the broker stored the event, not Kafka. The tables compare against Kafka's count for that reason. With persistence on and a persistent session, an event the broker acknowledged survives an event-ingest restart; it does not survive losing the broker's disk, so a production broker needs replication.
- The test broker allows anonymous clients. A real deployment authenticates each SIM with a client certificate and limits each device to its own topic.
- The per-customer rate limit equalled the test rate. A higher limit removes the HTTP refusals; it was left as is to show how each protocol handles a burst.
- Polling was measured at 1,000 lines, and the full-scale cost is a straight-line projection.
