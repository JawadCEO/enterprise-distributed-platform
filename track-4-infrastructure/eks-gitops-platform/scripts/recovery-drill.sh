#!/usr/bin/env bash
# Recovery drill (FR-27, NFR-2): starts from a fresh, empty cluster and times
# the restore from Git, from "cluster ready" to every Application Synced and
# Healthy and the tenant app answering over HTTPS.
#
#   scripts/recovery-drill.sh local   deletes and recreates the k3d cluster
#   scripts/recovery-drill.sh dev     run on the access host right after
#                                     Terraform has recreated the cluster
#
# It never wipes a live cluster piece by piece: deleting add-ons and tenants
# in place is slower and less realistic than a new cluster, and the first
# drill showed it can hang on deletion order.
set -euo pipefail

cluster="${1:?usage: recovery-drill.sh <local|dev|prod>}"
here="$(cd "$(dirname "$0")/.." && pwd)"

if [[ "$cluster" == local ]]; then
  read -r -p "This deletes the local k3d cluster 'eks-gitops'. Type local to continue: " answer
  [[ "$answer" == local ]] || { echo "aborted"; exit 1; }
  k3d cluster delete eks-gitops
  k3d cluster create --config "$here/gitops/bootstrap/local/k3d.yaml" --wait
  kubectl config use-context k3d-eks-gitops
  host="podinfo-team-a.localtest.me"; port=8443
else
  # On AWS the cluster must be new and empty: Terraform recreated it.
  if kubectl get namespace argocd >/dev/null 2>&1; then
    echo "argocd already exists: run this on a freshly created cluster" >&2; exit 1
  fi
  host=""; port=443
fi

echo "Cluster ready. Restoring from Git"
start=$(date +%s)
"$here/scripts/bootstrap.sh" "$cluster"

if [[ -n "$host" ]]; then
  until curl -sk -o /dev/null -w '%{http_code}' "https://$host:$port/" | grep -q 200; do sleep 5; done
fi
elapsed=$(( $(date +%s) - start ))

echo
echo "Recovery time: ${elapsed}s (target < 600s, NFR-2)"
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $cluster recovery ${elapsed}s" >> "$here/docs/results/measurements.txt"
