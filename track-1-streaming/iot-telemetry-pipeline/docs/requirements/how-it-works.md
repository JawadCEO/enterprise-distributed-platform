# Carrier IoT platform: how it works

Agreed with Jawad on 2026-10-26; the requirement documents are built from it. Domain: a mobile carrier's IoT connectivity platform (like Verizon ThingSpace). This describes who sends what, what we do with it, and what comes back.

## 1. Who is involved

| Actor | Example | What they do |
|---|---|---|
| **Business customer** | A logistics company with 200,000 SIM-connected asset trackers | Owns lines (SIMs) and devices. Manages them through our API: activate, suspend, reconnect, check status |
| **Device** | A tracker on a truck | Connects to the cell network, sends a reading when something changes plus a keep-alive every few minutes |
| **Carrier network** | Cell towers and the core network (simulated) | Reports what devices do on the network: attached, detached, data used. Carries out line changes we request (suspend, restore) |
| **Our platform** | This project | Turns all of that into up-to-date state, and tells customers when something changes |

## 2. The problem, concretely

Today the customer learns everything by **polling**:

- To know if trackers are online, their system calls `GET /lines?status` for all 200,000 lines every 5 minutes. Almost every answer is "no change".
- After asking to reconnect a line, they call `GET /requests/{id}` again and again until it says done.
- To catch a tracker running out of data, they download usage reports and compare.

The carrier does the same internally, polling network systems for state. Across 5 million lines that is about 16,700 requests a second of mostly wasted work, and every change is seen minutes late.

**What we build instead:** the platform receives events as they happen and **pushes a notification** (a webhook) to the customer when something they care about changes. The customer stops polling.

## 3. What comes in

### a) Events from the network and devices (high volume, automatic)

| Event | Comes from | Meaning |
|---|---|---|
| `DEVICE_ATTACHED` | Network | Device connected to the network |
| `DEVICE_DETACHED` | Network | Device disconnected cleanly |
| `USAGE_RECORD` | Network | Data used in the last interval (bytes) |
| `DEVICE_STATUS` | Device | Battery, signal strength, firmware, fault code |
| `KEEP_ALIVE` | Device | "Still here, nothing changed" |
| `IMEI_CHANGED` | Network | The SIM was moved to a different device (possible theft) |
| `PROVISIONING_RESULT` | Network | Answer to a line change we asked for: done or rejected, with reason |

### b) Requests from customers (low volume, they change something)

| Request | What it does | Allowed when the line is |
|---|---|---|
| Activate | Turn on a new line (for eSIM/iSIM, after the profile is downloaded) | `INVENTORY` |
| Suspend | Pause service (e.g. truck parked for winter) | `ACTIVE` |
| **Restore (reconnect)** | Resume a suspended line | `SUSPENDED` |
| Deactivate | End service permanently | `ACTIVE` or `SUSPENDED` |
| Subscribe | Register a webhook URL and which notifications they want | Any |
| Download / switch profile | Remote SIM profile change, eSIM and iSIM only (Flow E) | `ACTIVE` or `INVENTORY` |

## 4. What we keep and give back

**Kept for every line:**
- Line state: `INVENTORY`, `PENDING`, `ACTIVE`, `SUSPENDED`, `DEACTIVATED`
- Connection state: `ONLINE` or `OFFLINE`, last seen, signal, battery
- Data used this billing cycle vs the plan's allowance
- History of state changes

**Pushed to the customer (webhooks):**
- A request finished (succeeded or failed, with the reason)
- A device went offline unexpectedly, or came back online
- Usage crossed 80% or 100% of the plan
- The SIM moved to a different device

**Available by API:** current state of a line, its history, and the status of a request (for customers who still want to check).

## 4b. What we do with the data

Every event ends up serving one of seven purposes. Anything that serves none of them is not stored.

| # | Purpose | Built from | Who uses it | Kept for |
|---|---|---|---|---|
| 1 | **Current state of every line**: line state, online or offline, battery, signal, data used | All events, latest value wins | Customer API, operations | Always (one row per line) |
| 2 | **Notifications instead of polling**: request done, device offline or online, usage threshold, SIM moved | State changes | Customer systems (webhooks) | 30 days, for redelivery |
| 3 | **Usage metering**: data used per line per billing cycle | `USAGE_RECORD`, counted exactly once | Threshold alerts here; the totals feed the carrier's billing system (billing itself is out of scope) | 13 months |
| 4 | **History for support**: what a device reported and when, every state change | Status changes, attach/detach, requests | Support engineers, customer API | 90 days |
| 5 | **Fleet health per customer**: % of lines offline, by region and device model; requests failing | Aggregates of states | Operations dashboards, outage detection (the "don't send 500,000 alerts" guard) | 90 days, by the minute |
| 6 | **Security signals**: SIM moved to another device, a sudden jump in data use | `IMEI_CHANGED`, usage | Customer (webhook), carrier fraud team | 1 year |
| 7 | **Replay and audit**: the raw events as received | Everything | Engineers, to rebuild state after a bug or re-run a failed consumer | 7 days in Kafka |

**Not done with the data** (out of scope): analytics and machine learning on readings, alert rules on sensor values (e.g. "temperature above 8°C"), and selling or sharing data.

## 4c. SIM types

A line is a SIM, and the SIM's form changes what the platform can do and what some events mean. Each line records its `simType`.

| SIM type | What it is | Used in | Swap to another device? | How it's activated |
|---|---|---|---|---|
| **Removable** (plastic, 2FF/3FF/4FF) | The familiar card in a tray | Older trackers, routers, consumer-style devices | Yes, by hand | Card shipped to the customer, then activated by API |
| **Embedded eSIM** (MFF2, **soldered** to the board, an eUICC) | A SIM chip soldered onto the device's circuit board; profiles are downloaded over the air | Vehicles, meters, industrial devices, anything vibrating or outdoors | **No**, not without desoldering | A carrier profile is downloaded remotely, then enabled |
| **Integrated iSIM** (iUICC) | The SIM is built into the device's modem chip itself; no separate chip | New low-power devices (LTE-M, NB-IoT sensors) | **No**, physically impossible | Like eSIM: profile downloaded remotely |

### What changes because of SIM type

| Situation | Removable | Embedded eSIM / iSIM |
|---|---|---|
| `IMEI_CHANGED` (SIM appears in a different device) | Possible and often legitimate (a technician moved the card). **Medium** alert to the customer | Should be impossible. Means the device's modem was replaced, or the SIM identity is cloned. **High** alert to the customer and the carrier fraud team, and the line can be auto-suspended if the customer has opted in |
| Activating a new line | Line exists from manufacture; the customer activates it | The device first needs a profile: **download profile → enable profile → activate line** |
| Changing carrier or plan region | Swap the card | Remote profile switch, no site visit (see Flow E) |
| "SIM removed" event | Possible (device reports no SIM) | Never |

### Flow E (eSIM and iSIM only): remote profile download and switch

Embedded and integrated SIMs hold carrier profiles that can be changed over the air, following the GSMA standard for IoT eSIM (SGP.32). This adds two customer requests and three events:

- Requests: `DOWNLOAD_PROFILE`, `SWITCH_PROFILE` (e.g. move a meter from a roaming profile to a local one)
- Events from the network: `PROFILE_DOWNLOADED`, `PROFILE_ENABLED`, `PROFILE_FAILED`

1. The customer asks to switch the profile on 10,000 meters. Validation: lines belong to the customer, every line is eSIM or iSIM (a removable line gets `409 NOT_SUPPORTED_FOR_SIM_TYPE`), the target profile is allowed on their plan, no other request is in progress per line.
2. The request becomes 10,000 commands on Kafka, one per line, keyed by line ID. The provisioning worker paces them so the network isn't flooded.
3. Each device downloads and enables the new profile. **It disconnects and reconnects in the middle**, so the platform must expect a `DEVICE_DETACHED` during a switch and **not send an offline alert** for it.
4. If a device fails to enable the new profile, it falls back to the old one (`PROFILE_FAILED`), and the request fails with the reason.
5. The customer receives one summary webhook for the bulk job (succeeded / failed counts), plus one per failed line.

## 5. Walkthroughs

### Flow A: customer reconnects a suspended line

1. **Request.** The customer sends `POST /lines/{lineId}/restore` with an `Idempotency-Key` header.
2. **Validate, in this order; stop at the first failure:**

| Check | If it fails |
|---|---|
| API key or token is valid, and identifies the customer | `401` |
| The line belongs to this customer | `404` (don't reveal other customers' lines) |
| The customer is within its rate limit | `429` with a retry-after time |
| The `Idempotency-Key` was not used before | If used: return the **original** answer again; nothing new happens |
| The line is in a state that allows restore (`SUSPENDED`) | `409` naming the current state |
| No other request is already in progress on this line | `409` naming the request in progress |
| The account is in good standing (not blocked for non-payment) | `403` with reason |

3. **Accept.** The request is written to Kafka (topic `line.commands`, keyed by line ID so requests for one line stay in order). The line moves to `PENDING`. The customer gets `202 Accepted` with a `requestId`.
4. **Carry out.** A provisioning worker reads the command and asks the network to restore the line. The network is slow and sometimes fails, so the worker retries with back-off. If there is no answer within a time limit (say 2 minutes), the request fails with `NETWORK_TIMEOUT`.
5. **Confirm.** The network sends `PROVISIONING_RESULT: done`. The line moves to `ACTIVE`, and the request is marked `COMPLETED`.
6. **Device comes back.** The tracker reconnects and the network sends `DEVICE_ATTACHED`. The line becomes `ONLINE`.
7. **Notify.** The customer receives two webhooks: "restore completed", then "device online". They never polled.

**If it goes wrong:**
- The network rejects the restore → line goes back to `SUSPENDED`, request `FAILED` with the network's reason, webhook sent.
- The customer's webhook endpoint is down → we retry with back-off for up to 24 hours, then park the notification in a dead-letter store. The customer can still read it from the API.
- The customer sends the same request twice → the second call returns the first `requestId`; the line is restored once.

### Flow B: a device disconnects

1. Either the network sends `DEVICE_DETACHED`, or the device's keep-alives simply stop (battery dead, out of coverage).
2. **Validate the event:** known line, correct format, not a duplicate (event ID already seen), not older than the state we hold (per-line sequence number).
3. **Process:**
   - A detach marks the line `OFFLINE` straight away.
   - For silence, a presence monitor marks it `OFFLINE` once no keep-alive arrives within the device's window plus a grace period.
   - If the line was `SUSPENDED` by the customer, going offline is expected and **no alert is sent**. Only unexpected disconnects are notified.
4. **Notify:** "device offline" webhook, and "device online" when it reconnects.
5. **Guard:** if a whole region goes quiet at once (a network outage, or our own outage), we don't send 500,000 "offline" alerts. We send one outage notice per customer instead.

### Flow C: data usage crosses the limit

1. `USAGE_RECORD` events arrive for each line.
2. **Validate:** known line, not a duplicate (a double-counted usage record would overcharge).
3. **Process:** add the bytes to the line's total for this billing cycle.
4. **Notify** once at 80% and once at 100% of the plan. Never twice for the same threshold.

### Flow D: SIM moved to another device

1. `IMEI_CHANGED` arrives: the SIM is now in a device it wasn't in before.
2. Record it in history and send a "SIM moved" webhook. It may be theft, or a legitimate repair. The customer decides.

## 6. Services this implies

| Service | Job |
|---|---|
| `customer-api` | Customer requests (Flow A steps 1 to 3), status queries, webhook subscriptions |
| `event-ingest` | Accepts network and device events, validates format, writes them to Kafka |
| `state-processor` | Applies events and request results to line state, in order per line, without duplicates (Flows B, C, D) |
| `presence-monitor` | Finds lines whose keep-alive window has passed; applies the outage guard |
| `provisioning-worker` | Carries out line changes against the network, with retries and timeouts (Flow A steps 4 and 5) |
| `notifier` | Sends webhooks, retries failures, keeps undeliverable ones |
| `network-simulator` (test tool) | Plays the carrier network and millions of devices, including faults on demand |

## 7. What changed from the first draft of the docs

- The first draft only covered **events coming in** (Flows B and C, roughly). It has no **customer requests** (Flow A), no **webhooks**, and no **line states**. Those are the telecom-specific parts, and they make the project clearly telecom.
- `presence-monitor` stays a separate service, because it scales with the number of lines rather than the event rate.
- Tenants are now called customers throughout.

## 8. Scope

Flows A to D are in scope. Flow E (remote profile switch) is P2 (open question 7). Notifications are delivered by webhook. The requirement documents in this folder turn this picture into numbered requirements.
