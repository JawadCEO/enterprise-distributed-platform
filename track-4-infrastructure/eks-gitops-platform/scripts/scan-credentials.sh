#!/usr/bin/env bash
# Zero static credentials check (NFR-7): the repository and its history,
# then (if a cluster is reachable) every Kubernetes secret.
#
#   scripts/scan-credentials.sh
set -euo pipefail

repo="$(git rev-parse --show-toplevel)"
status=0

echo "== Repository history (gitleaks)"
gitleaks detect --source "$repo" --no-banner --redact || status=1

if kubectl version --request-timeout=5s >/dev/null 2>&1; then
  echo "== Cluster secrets: looking for AWS access keys"
  hits="$(kubectl get secrets -A -o json \
    | grep -oE '"[A-Za-z0-9+/=]{20,}"' | tr -d '"' \
    | while read -r v; do printf '%s' "$v" | base64 -d 2>/dev/null | grep -aoE '(AKIA|ASIA)[A-Z0-9]{16}' || true; done | sort -u)"
  if [[ -n "$hits" ]]; then echo "AWS keys found in cluster secrets"; status=1; else echo "No AWS keys in cluster secrets"; fi
fi

echo "== Reminder: check GitHub > Settings > Secrets and variables has no AWS keys (role ARNs as variables are fine)"
exit $status
