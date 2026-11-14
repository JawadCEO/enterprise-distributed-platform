#!/usr/bin/env bash
# Waits until every ArgoCD Application is Synced and Healthy, and prints the
# elapsed time. Used by bootstrap and by the recovery drill (NFR-2).
#
#   scripts/wait-for-sync.sh [timeout-seconds]
set -euo pipefail

timeout="${1:-900}"
start=$(date +%s)

while true; do
  # name sync health, one line per Application
  status="$(kubectl get applications.argoproj.io -n argocd \
    -o jsonpath='{range .items[*]}{.metadata.name} {.status.sync.status} {.status.health.status}{"\n"}{end}' 2>/dev/null || true)"
  total=$(grep -c . <<<"$status" || true)
  pending="$(grep -v ' Synced Healthy$' <<<"$status" || true)"
  elapsed=$(( $(date +%s) - start ))

  if [[ "$total" -gt 0 && -z "$pending" ]]; then
    echo "All $total Applications Synced and Healthy after ${elapsed}s"
    exit 0
  fi
  if (( elapsed > timeout )); then
    echo "Timed out after ${elapsed}s. Not ready:" >&2
    echo "$pending" >&2
    exit 1
  fi
  echo "[${elapsed}s] waiting on: $(awk '{print $1}' <<<"$pending" | paste -sd ' ' -)"
  sleep 10
done
