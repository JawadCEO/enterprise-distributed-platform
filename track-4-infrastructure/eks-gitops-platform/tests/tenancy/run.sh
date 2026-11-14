#!/usr/bin/env bash
# Live tenancy, policy and drift checks against a running cluster
# (FR-8, FR-18, FR-20, NFR-5). team-b plays the noisy neighbour.
#
#   tests/tenancy/run.sh
#
# Prints PASS/FAIL per check and exits non-zero if any check fails.
set -uo pipefail

fails=0
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; fails=$((fails + 1)); }

probe() {  # probe <namespace> <name> <url>: one-off curl pod inside a tenant
  kubectl -n "$1" run "$2" --rm -i --restart=Never --quiet \
    --image=ghcr.io/stefanprodan/podinfo:6.7.1 \
    --labels="app.kubernetes.io/name=$2,owner=test" \
    --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":10001,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"'"$2"'","image":"ghcr.io/stefanprodan/podinfo:6.7.1","command":["sh","-c","wget -q -T 5 -O- '"$3"' >/dev/null && echo reachable || echo blocked"],"resources":{"requests":{"cpu":"10m","memory":"16Mi"},"limits":{"memory":"32Mi"}},"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}' \
    2>/dev/null | tail -1
}

echo "== Network isolation (FR-18)"
[[ "$(probe team-a probe-same http://podinfo.team-a.svc)" == reachable ]] \
  && pass "team-a reaches its own service" || fail "team-a cannot reach its own service"
[[ "$(probe team-b probe-cross http://podinfo.team-a.svc)" == blocked ]] \
  && pass "team-b is blocked from team-a" || fail "team-b reached team-a"

echo "== Quota (FR-17)"
# A compliant deployment asking for 100 replicas; the quota must cap it.
samples="$(dirname "$0")/../policies/samples"
sed 's/namespace: team-a/namespace: team-b/; s/^  name: good$/  name: hog/; s/^spec:$/spec:\n  replicas: 100/' \
  "$samples/good-deployment.yaml" | kubectl apply -f - >/dev/null
sleep 20
created=$(kubectl -n team-b get pods --no-headers 2>/dev/null | grep -c '^hog-' || true)
# Some pods must start (so the sample itself is valid) and the quota must
# stop the rest. Zero means something else blocked them, which is a failure.
(( created > 0 && created < 100 )) && pass "noisy neighbour capped at $created of 100 pods by its quota" \
  || fail "noisy neighbour created $created of 100 pods"
kubectl -n team-b delete deployment hog --wait=false >/dev/null

echo "== Admission policies (FR-20)"
for f in "$(dirname "$0")"/../policies/samples/bad-*.yaml; do
  name=$(basename "$f" .yaml)
  if kubectl apply --dry-run=server -f "$f" >/dev/null 2>&1; then
    fail "$name was admitted"
  else
    pass "$name rejected"
  fi
done
kubectl apply --dry-run=server -f "$(dirname "$0")/../policies/samples/good-deployment.yaml" >/dev/null 2>&1 \
  && pass "compliant deployment admitted" || fail "compliant deployment rejected"

echo "== Drift revert (FR-8, NFR-5)"
kubectl -n team-a scale deployment podinfo --replicas=5 >/dev/null
start=$(date +%s)
while (( $(date +%s) - start < 300 )); do
  [[ "$(kubectl -n team-a get deployment podinfo -o jsonpath='{.spec.replicas}')" == 2 ]] && break
  sleep 5
done
elapsed=$(( $(date +%s) - start ))
[[ "$(kubectl -n team-a get deployment podinfo -o jsonpath='{.spec.replicas}')" == 2 ]] \
  && pass "manual scale reverted by ArgoCD in ${elapsed}s" || fail "manual scale not reverted within 300s"

echo
(( fails == 0 )) && echo "All checks passed" || echo "$fails check(s) failed"
exit $(( fails > 0 ))
