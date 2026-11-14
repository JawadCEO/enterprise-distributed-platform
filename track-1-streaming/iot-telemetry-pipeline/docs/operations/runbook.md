# iot-telemetry-pipeline: Runbook

What to do when the platform needs a change or something goes wrong. The manifests are in [`deploy/k8s`](../../deploy/k8s/README.md); the decisions behind them are ADR-009 and ADR-010 in [architecture decisions](../requirements/architecture-decisions.md). Commands assume the `iot` namespace (`kubectl config set-context --current --namespace iot`).

## Deploy

1. Build and push the images with one new tag for every service.
2. Set that tag in the overlay's `images:` list and apply it: `kubectl apply -k deploy/k8s/overlays/production`.
3. Watch each Deployment: `kubectl rollout status deploy/<service>`. A pod is replaced only after its successor is ready, so a new version that never becomes ready stops the rollout with the old pods still serving.
4. If the release has a database migration, it runs when the first new customer-api pod starts. The other services wait for the schema in their `wait-for-schema` init container, so deploy customer-api first if you deploy services one at a time.

Migrations follow expand and contract. A release may add tables, columns, indexes or allowed values that the previous version ignores. It may not rename, drop or tighten anything the previous version still uses; that waits for the next release. This is what makes the rollback below safe, because Flyway migrations are never undone.

## Roll back

`kubectl rollout undo deploy/<service>` returns one service to its previous version in one step. Each Deployment keeps five revisions; `kubectl rollout history deploy/<service>` lists them and `--to-revision=N` picks one. Then set the overlay's image tag back too, or the next `apply` undoes the rollback.

## Scale

| Service | Scales on | Range | Limit |
|---|---|---|---|
| event-ingest | CPU 50% (HPA) | 3 to 14 in production | none known below the brokers |
| state-processor | `network.events` lag, one pod per 5,000 behind (KEDA) | 2 to 43 | 128 partitions at 3 threads each; add partitions to go further |
| customer-api, presence-monitor, provisioning-worker, notifier | fixed (`sizes.yaml`) | 2 or more | provisioning-worker is paced by the network's rate (NETWORK_RATE), not by pods |

To scale by hand during an incident, change the HPA's or ScaledObject's minimum, not the Deployment's replicas: the autoscaler would set the Deployment back.

## Alerts and what to do

| Signal | Means | Do |
|---|---|---|
| Freshness p95 over 50 ms (`iot_freshness_seconds`) | Events take too long from the network to the line's state | Look at the hop panel (`iot_freshness_hop_seconds{hop}`) to find the slow hop. Lag rising too: see below. Database hop slow: check PostgreSQL CPU and locks |
| Consumer lag rising (`kafka_consumergroup_lag`) | state-processor is behind | Check that KEDA added pods (`kubectl get hpa,scaledobject`). At the maximum, find the hot partitions: a few lagging partitions means a few busy lines, not too few pods |
| Dead letters rising (`iot_state_events_total{outcome="dead_letter"}`) | Events the processor cannot apply | `GET /v1/dead-letters` groups them by reason. Fix the cause, then `POST /v1/dead-letters/replay` with `reasonCode` and optionally `customerId`, `lineId`, `from`, `to`, `limit`. Replay is safe to repeat: events are applied once (ADR-006) |
| Webhook backlog for one customer (`iot_notifier_backlog{customer}`) | That customer's endpoint is down or slow | Nothing to fix on our side; other customers are not affected (ADR-008). Tell the customer. After 24 h notifications park; once the endpoint is back, redeliver with `POST /v1/notifications/{id}/redeliver` |
| Webhook backlog for every customer | The notifier itself is stuck | Check notifier pods and their logs; restart with `kubectl rollout restart deploy/notifier`. Deliveries resume at least once with the same `notificationId` |
| Requests pending too long (`iot_requests_pending`, completion p95) | The network is slow or not answering | The sweeper fails requests after 120 s and restores the line's state, so nothing is stuck for ever. Check the network's status and `iot_requests_finished_5m{reason}` |
| Outage open or presence paused (`iot_presence_outages_open`, `iot_presence_paused`) | A region's events stopped arriving, so offline alerts are held back | Confirm with the network operations centre. The outage guard ends by itself when events return; no action unless it does not |
| Many lines offline at once with no outage | Devices really went away, or keep-alive windows are too short | Check `iot_presence_offline_marked_total` by customer and the lines' keep-alive settings |

## When a dependency is down

| Down | What happens | Do |
|---|---|---|
| One Kafka broker | Ingest pauses about 10 s while partition leaders move, then runs on two brokers; writes still need two of three copies (`min.insync.replicas` 2) | Replace the broker; its partitions catch up by themselves |
| Two Kafka brokers | Writes cannot get two copies, so event-ingest stops acknowledging and senders resend | Restore a broker. Nothing acknowledged is lost |
| PostgreSQL | Ingest keeps accepting from its caches; a line not cached yet gets 503 with `Retry-After: 1`. Processing pauses and catches up from Kafka once the database is back | Fail over to the standby; nothing to replay |
| Redis | Each call waits for the 200 ms Redis timeout and uses the pod's local rate limit; duplicate checks fall back to the database. presence-monitor puts back missing offline deadlines every 2 minutes | Restore Redis; check `iot_presence_deadlines_repaired_total` rose |
| A whole region | Traffic moves to the other region | See ADR-004 and the [part 5 results](../results/carrier-5-scale-2026-11-11.md): events not yet copied arrive when the region returns, so do not wipe it |

The one-broker, PostgreSQL and Redis rows were measured in the [part 3 drills](../results/carrier-3-drills-2026-11-11.md) and the region row in part 5. The two-broker row follows from the producer settings (`acks=all`) and has not been drilled.

## Secrets

`iot-secrets` holds `DB_PASSWORD` and `IOT_OPERATOR_KEY`. To rotate one:

1. Database password: add the new password to the database user (or a second user), update the secret, `kubectl rollout restart` every service, then remove the old password.
2. Operator key: issue a new key, update the secret and restart, then revoke the old one with `POST /v1/api-keys/{keyId}/revoke`.
3. Customer and network keys: the customer or operator issues a new key, moves to it, then revokes the old one. Webhook signing secrets are replaced by creating a new endpoint and deleting the old one.

Never put a production secret in a file in the repository; the local overlay's generated secret holds development keys only.
