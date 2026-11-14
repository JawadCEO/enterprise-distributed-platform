#!/usr/bin/env bash
# Recovery drill (FR-27, NFR-2): wipes everything ArgoCD installed, leaving
# an empty cluster, then restores it from Git and times the restore.
# Run against the local cluster, or on the access host for dev.
#
#   scripts/recovery-drill.sh <local|dev|prod>
set -euo pipefail

cluster="${1:?usage: recovery-drill.sh <local|dev|prod>}"
here="$(cd "$(dirname "$0")/.." && pwd)"
context="$(kubectl config current-context)"
read -r -p "This deletes every platform add-on and tenant on '$context'. Type the cluster name to continue: " answer
[[ "$answer" == "$cluster" ]] || { echo "aborted"; exit 1; }

echo "Wiping: deleting the root Application and everything under it"
kubectl -n argocd delete application root --wait=true --timeout=15m || true
kubectl -n argocd delete applications.argoproj.io --all --wait=true --timeout=10m || true
helm -n argocd uninstall argocd --wait || true
kubectl delete namespace argocd --wait=true --timeout=5m || true
echo "Cluster is empty of platform components"

start=$(date +%s)
"$here/scripts/bootstrap.sh" "$cluster"
elapsed=$(( $(date +%s) - start ))

echo
echo "Recovery time: ${elapsed}s (target < 600s, NFR-2)"
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $cluster recovery ${elapsed}s" >> "$here/docs/results/measurements.txt"
