# Forcing each alert once (NFR-18)

Each alert in `gitops/platform/monitoring-config/base/alerts.yaml` is forced once and seen in Alertmanager (`kubectl -n monitoring port-forward svc/kube-prometheus-stack-alertmanager 9093`). Record the result in `docs/results/README.md`.

| Alert | How to force it | Clean up |
|---|---|---|
| PlatformNodeNotReady | AWS: stop one system node's EC2 instance. Local: `docker stop k3d-eks-gitops-agent-0` | Start it again |
| ArgoAppDegraded | Merge a podinfo image tag that does not exist | Revert the commit |
| ArgoAppOutOfSync | Disable auto-sync on `root` and on one Application in the UI (root would otherwise restore it), then change a value in Git | Re-enable auto-sync on both |
| ArgoSyncFailing | Merge a manifest with an invalid field | Revert the commit |
| CertificateExpiringSoon | Create a test Certificate with `duration: 360h` (15 days) and `renewBefore: 1h` | Delete it |
| TenantNearQuota | `kubectl -n team-b scale deployment podinfo --replicas=19` | Let ArgoCD revert it |
| GatekeeperUnavailable | Disable auto-sync on `root` and on the gatekeeper Application, then `kubectl -n gatekeeper-system scale deployment gatekeeper-controller-manager --replicas=0`; check a tenant deploy is refused meanwhile (ADR-008) | Re-enable auto-sync on both; ArgoCD restores the replicas |
