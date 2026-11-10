# Carrier IoT platform: what the real-time data looks like

Companion to [how-it-works.md](how-it-works.md). No carrier publishes real network data, so all of this comes from our `network-simulator`, built to behave like the real thing. The shapes below follow what carrier platforms such as Verizon ThingSpace expose: lines are identified by the SIM's ICCID, devices by IMEI.

## 1. Where it comes from and how fast

| Stream | Sent by | How often, per line | At 5M lines |
|---|---|---|---|
| Keep-alive | Device | Every 5 minutes when nothing changes | ~16,700/s |
| Device status (battery, signal, fault) | Device | When something changes; a few times an hour | ~3,000/s |
| Attach / detach | Network | Each time the device connects or disconnects; a few times a day (tunnels, dead zones, power cycles) | ~300/s |
| Usage record | Network | Every 15 minutes while data is flowing | ~5,500/s |
| SIM moved (IMEI change) | Network | Rare | <1/s |
| Customer requests | Customer API | Rare per line; bulk jobs of thousands at once | ~10/s, bursts of 1,000/s |
| **Total** | | | **~15,000/s on average, up to ~50,000/s at peak** |

Peaks are not random. They happen when:
- a cell region comes back after an outage, and every device in it reconnects within a minute or two;
- devices are set to report on the hour, so traffic spikes at :00;
- a customer runs a bulk job, such as suspending 50,000 trackers for winter.

## 2. What each event looks like

Every event shares one envelope. The `payload` changes by type.

```json
{
  "eventId": "0192e4c8-7b3a-7f21-9c4d-5a8e2f1b6c90",
  "type": "DEVICE_STATUS",
  "lineId": "89148000006254183375",
  "imei": "356938035643809",
  "customerId": "cust-logistics-0042",
  "bootId": 17,
  "sequence": 5512,
  "deviceTime": "2026-10-26T08:14:03Z",
  "payload": { "battery": 64, "signalDbm": -97, "firmware": "2.4.1", "fault": null }
}
```

- `eventId` is unique per event, so a resent event is recognised and dropped.
- `bootId` + `sequence` put one device's events in order, even after a reboot.
- `deviceTime` is the device's own clock, which can be wrong. The platform also stamps its own receive time.

The other payloads:

```json
{ "type": "DEVICE_ATTACHED",  "payload": { "cellId": "311480-10342-7781", "rat": "LTE-M", "region": "us-central" } }
{ "type": "DEVICE_DETACHED",  "payload": { "cause": "POWER_OFF" } }
{ "type": "USAGE_RECORD",     "payload": { "periodStart": "2026-10-26T08:00:00Z", "periodEnd": "2026-10-26T08:15:00Z", "bytesUp": 18432, "bytesDown": 4096 } }
{ "type": "KEEP_ALIVE",       "payload": {} }
{ "type": "IMEI_CHANGED",     "payload": { "previousImei": "356938035643809", "newImei": "490154203237518" } }
{ "type": "PROVISIONING_RESULT", "payload": { "requestId": "req-7f3c", "action": "RESTORE", "result": "DONE" } }
```

A customer request:

```http
POST /v1/lines/89148000006254183375/restore
Authorization: Bearer <customer token>
Idempotency-Key: 4a1d-restore-truck-118

→ 202 Accepted
{ "requestId": "req-7f3c", "status": "PENDING" }
```

A webhook we push to the customer:

```json
{
  "notificationId": "ntf-01HB9",
  "type": "DEVICE_OFFLINE",
  "lineId": "89148000006254183375",
  "at": "2026-10-26T13:42:40Z",
  "reason": "KEEP_ALIVE_MISSED",
  "lastSeen": "2026-10-26T13:31:10Z"
}
```

## 3. One tracker's day

A tracker on a delivery truck, line `...3375`, keep-alive window 5 minutes.

| Time | Event | What the platform does | Customer gets |
|---|---|---|---|
| 06:58 | `DEVICE_ATTACHED` (truck starts) | Line goes `ONLINE` | "device online" webhook |
| 07:00–13:30 | `DEVICE_STATUS` every ~20 min, `USAGE_RECORD` every 15 min, `KEEP_ALIVE` in between | Updates battery, signal, data used | Nothing; no change worth telling |
| 10:12 | Tunnel: no events for 3 minutes | Within the 5-minute window plus grace: no action | Nothing |
| 11:40 | Usage passes 80% of the monthly plan | Records it | "80% of data used" webhook, once |
| 13:31 | Last keep-alive, then the battery dies | Waits out the window plus 30 s grace | |
| 13:42 | Still nothing | Marks the line `OFFLINE`, reason `KEEP_ALIVE_MISSED` | "device offline" webhook |
| 18:05 | Battery replaced: device reboots, `bootId` goes 17 → 18, `sequence` restarts at 1 | Accepts the lower sequence because `bootId` is higher | "device online" webhook |
| 19:30 | Customer sends **suspend** (truck parked for the weekend) | Validates, line `PENDING`, network confirms, line `SUSPENDED` | "suspend completed" webhook |
| 19:31 | `DEVICE_DETACHED` | Line `OFFLINE`, but expected because it is suspended | **No** offline alert |

## 4. The messy parts the simulator must produce

Real networks are not clean, so the simulator injects these on purpose. Each one tests a requirement:

| Fault | Real cause | What it tests |
|---|---|---|
| Duplicate events | Device resends when an acknowledgement is lost | Nothing counted twice (usage especially) |
| Events out of order | Retries, two network paths | Older events never overwrite newer state |
| Device reboot resets `sequence` | Power loss | The device doesn't freeze as "stale" |
| Wrong device clock | Cheap hardware, no time sync | Ordering doesn't trust `deviceTime` |
| Region-wide silence then a reconnect storm | Cell outage | No flood of offline alerts; the burst is absorbed |
| Malformed or unknown-line events | Bad firmware, decommissioned SIM | Rejected and set aside, without blocking others |
| One customer at 10x its rate | A runaway script | Other customers unaffected |
| Network rejects or times out a request | Provisioning system busy | Request fails cleanly and the customer is told why |

## 5. Optional real data

No public data has carrier network events. For realistic battery and signal curves, the simulator can optionally replay public sensor data (e.g. the Intel Berkeley Lab sensor dataset), mapped onto `DEVICE_STATUS` events. The rest stays synthetic.
