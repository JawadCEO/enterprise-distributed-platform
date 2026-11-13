# Kubernetes and rolling deploys, 2026-11-13

Part 6: Kubernetes manifests with autoscaling (lag-based for the processor), rolling deploys with no lost or duplicated status (NFR-22), one-step rollback, and the write-up: [ADR-009 and ADR-010](../requirements/architecture-decisions.md#adr-009-deployment-and-rollback), a note on ADR-004 from part 5, and the [runbook](../operations/runbook.md). Measured in a build container, not on the 16GB laptop; the laptop steps are below.

## Summary

| Check | Target | Result |
|---|---|---|
| Manifests are valid | Both overlays accepted by a Kubernetes API server | Local: 33 objects plus the simulator job. Production: every object except the KEDA ScaledObject, whose type is not installed there. **PASS** |
| Rolling deploy under traffic (NFR-22) | Every instance replaced, nothing lost or duplicated | 6 instances replaced during a 300 s faults run; 0 send errors; requests, usage and notifications all reconciled. **PASS** |
| Pods running on Kubernetes | The same drill on a cluster | **Not run here.** See below; run it on the laptop |

## What could not run here

The build container can run Docker but not a Kubernetes node inside Docker: k3s starts and its API server works, but every pod fails with `can't get final child's PID from pipe: EOF`, because this sandbox does not allow the nested containers a node needs. So the manifests were checked with `kubectl apply --dry-run=server`, which validates every field against the real API (including probes, security context, PDBs, HPAs, network policies and the topology spread), and the rolling deploy was measured on Compose instead, replacing instances in the same order a Deployment does. The laptop steps below run the real thing on Docker Desktop's Kubernetes.

## Rolling deploy under traffic (NFR-22)

`docker-compose.rolling.yml` runs two instances each of event-ingest, state-processor and notifier, with nginx in front of event-ingest the way a Service spreads traffic over pods, and the services set to shut down gracefully as the manifests do. `drills/rolling.sh` does what a rolling update with `maxSurge: 1, maxUnavailable: 0` does: start one new instance, wait until it is healthy, stop one old one with SIGTERM (up to 40 s to drain), repeat.

The faults scenario ran for 300 s with 1,000 lines at about 92 events/s and 3 customer requests/s, with duplicates, malformed events, reboots, silences, detaches and SIM moves. The roll started 60 s in:

| Service | Instances replaced | New instance healthy after |
|---|---|---|
| event-ingest | 2 | 11 s, 11 s |
| state-processor | 2 | 12 s, 16 s |
| notifier | 2 | 16 s, 16 s |

All six were replaced in 91 s. The simulator saw 0 send errors and 0 retries for the whole run, and ingest kept its rate (90 to 93 events/s). Its acknowledgement p95, counted from the start of the run, rose from 40 ms before the roll to 59 ms during it.

| Reconciliation | Result |
|---|---|
| Requests | 799 accepted: 740 completed, 53 failed as the network rejected, 6 timed out; 0 pending, 0 lines stuck PENDING, 0 not matching the network. **PASS** |
| Usage | 1,000 lines, 968 matched, 0 short, 0 over; 497,854,507 bytes acknowledged, 497,854,507 counted. **PASS** |
| Notifications | 1,056: 833 delivered and received, 0 more than once, 0 lost, 0 out of order per line, 0 bad signatures; 223 still pending for the customer whose endpoint is down on purpose. Event to webhook p50 23 ms, p95 90 ms. **PASS** |

### What the runs found

- **A false alarm in the simulator's own check.** The first run failed on 15 outage notices with no outage in the scenario. They came after traffic stopped: the request check waits up to two minutes for the last requests, every region was silent by then, and presence-monitor rightly called that an outage (5 customers times 3 regions). The check now counts only outage notices raised while traffic was running. The platform did nothing wrong.
- **`docker compose run` can undo scaling.** In the first two runs event-ingest and notifier had one instance, not two, because `run` without `--no-deps` set them back to one when it started their dependents. Those runs still passed with one instance each replaced; the third, above, used `--no-deps` and had two of each. The commands below include it.

## Autoscaling

| Service | Signal | Range |
|---|---|---|
| event-ingest | CPU 50% (HPA) | 2 to 10 in the base, 3 to 14 in production |
| state-processor | CPU 50% in the base; in production `network.events` lag for its group, one pod per 5,000 events behind (KEDA) | 2 to 4 in the base, 2 to 43 in production (128 partitions, 3 threads per pod) |

The lag trigger was validated as YAML only, since KEDA's type is not installed on the test API server. Its threshold comes from the part 5 rate (about one second of traffic per pod).

## Rollback

Each Deployment keeps five revisions, so `kubectl rollout undo deploy/<service>` goes back one step. Migrations are forward-only and must follow expand and contract (ADR-009), so the previous code always runs on the newer schema. Not tested on a running cluster here.

## Run it on the laptop

Docker Desktop: Settings, Kubernetes, enable it. The cluster shares Docker's images, so nothing is pushed. Each line is one command, from `track-1-streaming/iot-telemetry-pipeline`. Stop the Compose stack first; the platform on Kubernetes should need about 4 GB (an estimate from the Compose stack, not measured).

```
docker compose down
.\mvnw -q package -DskipTests
docker compose --profile sim build
foreach ($s in "customer-api","event-ingest","state-processor","presence-monitor","provisioning-worker","notifier","network-simulator") { docker tag iot/${s}:latest iot/${s}:local }
kubectl config use-context docker-desktop
kubectl apply -k deploy/k8s/overlays/local
kubectl -n iot get pods -w
```

When every pod is ready (customer-api first, then the others leave `Init`), press Ctrl+C and start traffic:

```
kubectl -n iot apply -f deploy/k8s/overlays/local/simulator-job.yaml
kubectl -n iot logs -f job/simulator
```

While it runs, in a second window, roll and then roll back:

```
kubectl -n iot rollout restart deploy/event-ingest deploy/state-processor deploy/notifier
kubectl -n iot rollout status deploy/state-processor
kubectl -n iot rollout undo deploy/state-processor
kubectl -n iot rollout history deploy/state-processor
```

The job's log ends with the three reconciliations. Clean up with `kubectl delete namespace iot`.

The Compose drill above, the same way it ran here:

```
docker compose -f docker-compose.yml -f docker-compose.rolling.yml up -d --build --scale event-ingest=2 --scale state-processor=2 --scale notifier=2
docker compose -f docker-compose.yml -f docker-compose.rolling.yml --profile drills run --no-deps -d --name rolling rolling
docker compose -f docker-compose.yml -f docker-compose.rolling.yml --profile sim run --no-deps --rm -e SCENARIO=faults -e LINES=1000 -e DURATION=300s -e REQUEST_RATE=3 network-simulator
docker logs rolling
docker rm -f rolling
docker compose -f docker-compose.yml -f docker-compose.rolling.yml down
```
