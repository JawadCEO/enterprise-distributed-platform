# Runbook: local platform (k3d)

Free, runs on the 16GB laptop, covers Phases 2, 3 and 6.

## Tools

| Tool | Tested version | Install (Windows, from an admin PowerShell) |
|---|---|---|
| Docker Desktop | 4.x | docker.com, then start it |
| k3d | 5.7+ (first run used 5.9.0) | `winget install k3d` |
| kubectl | 1.31+ | ships with Docker Desktop |
| Helm | 3.16+ or 4.x (first run used 4.3.2) | `winget install Helm.Helm` |
| gator | 3.18 | download `gator-v3.18.0-windows-amd64.tar.gz` from the Gatekeeper releases page |

Run the scripts from Git Bash (Start menu, "Git Bash"), not Command Prompt or PowerShell. In Git Bash, paths are written `/c/Preparation/...`.

## Bring it up

```bash
scripts/local-up.sh
```

This creates the k3d cluster, installs ArgoCD, applies the root Application, and waits until every Application is Synced and Healthy. It prints the elapsed time.

ArgoCD pulls from the `main` branch on GitHub, so local runs see only merged changes. To test a branch, set `targetRevision` in `gitops/root/values-local.yaml` and `gitops/bootstrap/local/root-app.yaml` to the branch name, and set it back before merging.

## Check it

```bash
gator verify tests/policies/        # policy suite, no cluster needed
tests/tenancy/run.sh                # isolation, quota, admission, drift revert
curl -k https://podinfo-team-a.localtest.me:8443
```

## Measure memory (NFR-6)

```bash
docker stats --no-stream
```

Record the total for the k3d containers in the results write-up.

## Tear down

```bash
k3d cluster delete eks-gitops
```
