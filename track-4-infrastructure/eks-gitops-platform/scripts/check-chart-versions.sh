#!/usr/bin/env bash
# Lists the pinned version of each add-on next to the newest release, so
# upgrades are a deliberate pull request (NFR-13, NFR-19).
#
#   scripts/check-chart-versions.sh [local|dev|prod]
set -euo pipefail

cluster="${1:-dev}"
here="$(cd "$(dirname "$0")/.." && pwd)"
catalog="$here/gitops/root/values.yaml"
pins="$here/gitops/root/values-$cluster.yaml"

printf '%-30s %-12s %s\n' ADD-ON PINNED LATEST
# Lines like:   cert-manager:  { chart: { version: "v1.16.2" } }
sed -n 's/^  \([a-z-]*\): *{.*version: "\([^"]*\)".*/\1 \2/p' "$pins" | while read -r name pinned; do
  repo="$(sed -n "/^  $name:/,/^  [a-z]/s/.*chart: { repo: \([^,]*\), name: \([^ ]*\) }.*/\1 \2/p" "$catalog" | head -1)"
  url="${repo% *}"; chart="${repo#* }"
  if [[ "$url" == http* ]]; then
    helm repo add "chk-$name" "$url" --force-update >/dev/null 2>&1
    latest="$(helm search repo "chk-$name/$chart" -o json 2>/dev/null | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' | head -1)"
  else
    latest="$(helm show chart "oci://$url/$chart" 2>/dev/null | sed -n 's/^version: //p')"
  fi
  printf '%-30s %-12s %s\n' "$name" "$pinned" "${latest:-unknown}"
done
