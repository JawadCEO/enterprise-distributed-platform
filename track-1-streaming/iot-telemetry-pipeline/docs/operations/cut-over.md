# iot-telemetry-pipeline: Cut-over from the legacy poller

How customers move from polling the carrier to the new platform's webhooks, without a big-bang switch and with a way back at every step (open question 4: the legacy poller is not rebuilt, only the move off it is planned).

## Starting point

Today customers poll for line state, device status, request outcomes and usage, and the carrier polls its own network systems the same way (see [how it works](../requirements/how-it-works.md)). The new platform takes the network's events as they happen, keeps every line's state, and pushes webhooks. Both must run side by side until every customer has moved.

## Stages

| Stage | What runs | Customers see | Leave the stage when |
|---|---|---|---|
| 1. Load | The registry is imported into the new platform: customers, then their lines in batches (`POST /v1/lines/batch`, each line with its current state), then a network key for event-ingest | Nothing changes | Every line in the legacy system exists in the new one with the same state |
| 2. Shadow | The network sends its events to the new platform as well as to the legacy systems. No webhooks are registered yet. A daily comparison reads both sides | Nothing changes | 14 days in a row with every line's state matching and usage matching to the byte for every line (the same check the simulator's reconciliations make) |
| 3. Pilot | A few customers register webhooks and read from the new API (`GET /v1/lines/{iccid}`, `/usage`, `/history`) while still able to poll the old one | Webhooks for those customers | The pilot customers confirm their integrations work, with no dead-lettered events and no webhook backlog for 7 days |
| 4. Waves | The remaining customers move in waves, smallest first. Line requests for a moved customer go through `POST /v1/lines/{iccid}/{action}` and bulk jobs only | Webhooks; the old polling API still answers, from the new platform's data | All customers moved |
| 5. Retire | Polling traffic is watched until it reaches zero, then the legacy pollers and API are switched off | Nothing; they already moved | 30 days with no polling calls |

## Comparing the two sides

Per line, the daily comparison checks:

- **State:** the line state (ACTIVE, SUSPENDED and so on) and whether the device is online.
- **Usage:** bytes for the current billing period. The new platform counts each usage event once (ADR-006). The legacy number comes from polling, so it can trail by up to one polling interval. A difference counts only if it remains after that interval.
- **Requests:** every request finished on the legacy side has the same outcome on the new side.

A mismatch stops the stage until it is explained. Each one is either a defect in the new platform or a known legacy error, and only the second kind is accepted.

## Going back

- **During stages 1 and 2:** stop sending events to the new platform. No customer has seen it.
- **During stages 3 and 4:** move a customer back by deleting their webhooks (`DELETE /v1/webhooks/{id}`) and pointing them back at the old API, which keeps running until stage 5.
- **After stage 5:** there is no way back to the legacy system. Recovery uses the runbook and the platform's own disaster recovery (ADR-004).

## Risks

| Risk | What limits it |
|---|---|
| A customer's endpoint can't take webhook bursts | Per-endpoint concurrency and back-off, with parking after 24 h (ADR-008). Bulk jobs send one summary, not one webhook per line |
| Usage disagrees and affects billing | Billing stays on the legacy numbers until stage 2 shows 14 matching days |
| The network can't send events to two places | event-ingest is the only new endpoint; the network's feed can be copied by a relay in front of both |
| A wave overloads the platform | Waves are sized by line count against the part 5 load figures, and per-customer rate limits stay on |
