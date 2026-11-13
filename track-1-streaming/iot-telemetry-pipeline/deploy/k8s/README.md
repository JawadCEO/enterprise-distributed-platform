# Kubernetes manifests

Kustomize, applied with `kubectl apply -k`. Why it is built this way: ADR-009 and ADR-010 in [architecture decisions](../../docs/requirements/architecture-decisions.md). How to run it: the [runbook](../../docs/operations/runbook.md).

| Path | What it holds |
|---|---|
| `base/` | The six services, each a Deployment, PodDisruptionBudget and Service; shared settings (`config.yaml`); network policies; CPU autoscaling for event-ingest and state-processor |
| `components/keda-lag/` | Replaces state-processor's CPU autoscaler with a KEDA ScaledObject on `network.events` lag. Needs KEDA installed in the cluster |
| `overlays/local/` | One cluster on a laptop: Kafka, PostgreSQL, Redis and the simulated network added, development secrets, one replica each, images built locally |
| `overlays/production/` | Managed Kafka, PostgreSQL and Redis endpoints, 128 partitions with 3 replicas, sizes per service, pods spread over zones, lag autoscaling. Creates no secret: `iot-secrets` comes from the cluster's secret store |

Every service pod:

- runs as a non-root user with a read-only root filesystem;
- has startup, liveness and readiness probes on Spring's `/actuator/health/liveness` and `/readiness`;
- is replaced with `maxSurge: 1` and `maxUnavailable: 0`, and drains for up to 40 s when stopped (5 s to leave the Service, then graceful shutdown);
- except customer-api, waits in an init container until customer-api's Flyway migrations have created the schema.

Only event-ingest (8080) and customer-api (8084) accept traffic from outside the namespace.

Check what an overlay produces without applying it:

```
kubectl kustomize deploy/k8s/overlays/production
kubectl apply -k deploy/k8s/overlays/production --dry-run=server
```
