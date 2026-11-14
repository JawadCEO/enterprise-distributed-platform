#!/usr/bin/env bash
# One-time bootstrap of a cluster (FR-5, flow F2): installs ArgoCD and the
# root Application. After this, nothing touches the cluster except ArgoCD.
#
#   scripts/bootstrap.sh local     # k3d cluster from scripts/local-up.sh
#   scripts/bootstrap.sh dev       # run on the access host, over SSM
#
# Idempotent: running it again on a working cluster changes nothing (NFR-17).
set -euo pipefail

cluster="${1:?usage: bootstrap.sh <local|dev|prod>}"
here="$(cd "$(dirname "$0")/.." && pwd)"
values_file="$here/gitops/root/values-$cluster.yaml"
[[ -f "$values_file" ]] || { echo "unknown cluster: $cluster" >&2; exit 1; }

# Use the same ArgoCD chart version the root chart pins for this cluster,
# so ArgoCD's self-management takes over without an upgrade.
argocd_version="$(sed -n 's/^  argocd: *{ chart: { version: "\([^"]*\)" } }.*/\1/p' "$values_file")"
[[ -n "$argocd_version" ]] || { echo "no ArgoCD version pinned in $values_file" >&2; exit 1; }

start=$(date +%s)
echo "Installing ArgoCD chart $argocd_version on $cluster"
helm repo add argo https://argoproj.github.io/argo-helm --force-update >/dev/null
kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
# Platform namespace: exempt from Gatekeeper's fail-closed webhook (ADR-008).
kubectl label namespace argocd admission.gatekeeper.sh/ignore=platform --overwrite
helm upgrade --install argocd argo/argo-cd \
  --namespace argocd \
  --version "$argocd_version" \
  --values "$here/gitops/platform/argocd/values.yaml" \
  --wait --timeout 10m

echo "Applying the root Application"
kubectl apply -f "$here/gitops/bootstrap/$cluster/root-app.yaml"

echo "Waiting for every Application to be Synced and Healthy"
"$here/scripts/wait-for-sync.sh" 1200
echo "Bootstrap finished in $(( $(date +%s) - start ))s"
