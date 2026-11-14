#!/usr/bin/env bash
# Creates the local k3d cluster and bootstraps it (Phases 2, 3, 6).
# Needs Docker Desktop running, plus k3d, kubectl and helm on PATH.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"

for tool in docker k3d kubectl helm; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done
docker info >/dev/null 2>&1 || { echo "Docker is not running" >&2; exit 1; }

if ! k3d cluster list eks-gitops >/dev/null 2>&1; then
  k3d cluster create --config "$here/gitops/bootstrap/local/k3d.yaml" --wait
fi
kubectl config use-context k3d-eks-gitops

"$here/scripts/bootstrap.sh" local

cat <<'EOF'

Local platform is up.
  ArgoCD UI:   kubectl -n argocd port-forward svc/argocd-server 8081:80, then http://localhost:8081
  Password:    kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
  Tenant app:  https://podinfo-team-a.localtest.me:8443  (self-signed CA, see docs)
  Tear down:   k3d cluster delete eks-gitops
EOF
