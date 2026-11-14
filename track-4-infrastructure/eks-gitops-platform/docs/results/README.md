# eks-gitops-platform: Results

**Status:** first local run done on 2026-11-14; AWS not run. Every row below is filled from a real run; until then it says "not run". Local figures come from the 16GB laptop; AWS figures from a dev session. Raw timings are appended to [measurements.txt](measurements.txt) by the scripts.

## Targets

| Target | Requirement | How measured | Local | AWS |
|---|---|---|---|---|
| Provisioning < 15 min | NFR-1, G2 | `eks-gitops terraform` apply job summary (time per stage) plus `scripts/bootstrap.sh` time; 3 runs | n/a | not run |
| Recovery < 10 min | NFR-2, G3 | `scripts/recovery-drill.sh` | not run | not run |
| Change lead time < 5 min | NFR-4 | Merge time to new version running | not run | not run |
| Cluster drift reverted < 5 min | NFR-5 | `tests/tenancy/run.sh` drift check | **5–7 s** (2 runs) | not run |
| Infrastructure drift reported < 24 h | NFR-5 | `eks-gitops drift` workflow after a console change | n/a | not run |
| Local memory ≤ 6GB | NFR-6 | `docker stats` with all add-ons | **4.4 GB** (k3d server 2.6 GB + agent 1.8 GB, 17 Applications) | n/a |
| Zero static credentials | NFR-7, G4 | `scripts/scan-credentials.sh`, `scripts/test-secret-scanner.sh` | not run | not run |
| API not reachable from the internet | NFR-8 | Connection attempt from outside the VPC | n/a | not run |
| Policies: bad rejected, good admitted | FR-20, G6 | `gator verify tests/policies/` and live dry runs | **Pass**: 5 of 5 bad samples rejected, compliant one admitted | not run |
| Tenant isolation and quota | FR-18, G5 | `tests/tenancy/run.sh` | **Pass**: team-b blocked from team-a; 100-replica deployment capped at 3 pods | not run |
| Every alert fires once | NFR-18 | [tests/alerts](../../tests/alerts/README.md) | not run | not run |
| 100% of resources tagged | NFR-25 | `scripts/check-tags.sh` | n/a | not run |
| Session cost per hour | NFR-23 | `scripts/cost-report.sh` the day after | n/a | not run |
| Idle cost per month ≤ $3 | NFR-24 | Cost Explorer on a day without a session | n/a | not run |

## Drills

| Drill | Result |
|---|---|
| Destroy and rebuild from nothing | not run |
| Restore onto an empty cluster | not run |
| Add-on upgrade, dev then prod promotion | not run |
| Kubernetes minor-version upgrade | not run |
| Bad sync rolled back by revert | not run |

## Projections

Figures beyond what was run (more tenants, larger nodes) go here, labelled as projections and derived from the measured rows above.

## Static checks (2026-11-10)

Everything CI runs that needs no cluster and no AWS, run on a Linux machine.

| Check | Result |
|---|---|
| `gator verify tests/policies/` (4 policies, 9 cases) | PASS |
| `helm lint` and `helm template` of the root chart for local, dev and prod | PASS (13, 15 and 15 resources) |
| `helm template` of every tenant file (dev, local x2, prod) | PASS (12 resources each) |
| Every tenant app (`gitops/apps/podinfo`) against the policies with `gator test` | PASS |
| `kubeconform` on all rendered and raw manifests, Kubernetes 1.31, CRD schemas included | 137 valid, 0 invalid, 5 skipped (no schema published) |
| `shellcheck -S warning` on `scripts/` and `tests/` | No findings |
| `terraform fmt -check -recursive terraform` | Failed on 3 files (alignment only). Fixed: `access-host`, `cluster` and `workload-identity` `main.tf` |
| `terraform init` and `terraform validate` | Not run: the Terraform registry was not reachable from that machine. The `eks-gitops checks` workflow runs both on the pull request |

Live checks (`scripts/local-up.sh`, `tests/tenancy/run.sh`, memory) still need the laptop: Argo CD pulls the charts and this repository from the internet, so they could not run on the machine used for the static checks.

## First local run (2026-11-14)

k3d on the 16GB Windows laptop, Docker Desktop with 7.6 GB, Helm 4.3.2, k3d 5.9.0.

| Check | Result |
|---|---|
| All Applications Synced and Healthy | 17 of 17 (13 add-on and config apps, root, 2 tenants, 2 tenant apps), after the fixes below |
| Tenant app over HTTPS | `https://podinfo-team-a.localtest.me:8443` returns 200, certificate issued by the platform CA |
| Secret delivered through External Secrets | The page shows the tenant's test greeting from its secret store |
| Plain HTTP | 301 redirect to HTTPS |
| `tests/tenancy/run.sh` | All 10 checks pass |
| Memory | 4.4 GB for the whole cluster |

Bugs found and fixed by this run:

| Symptom | Cause | Fix |
|---|---|---|
| kubectl could not reach the new cluster | k3d on Windows wrote `host.docker.internal` into the kubeconfig, which resolved to an unreachable LAN address | API pinned to `127.0.0.1:6550` in `k3d.yaml` |
| kube-prometheus-stack stuck: its ClusterRoles were never created | Gatekeeper's fail-closed webhook also matched cluster-scoped objects, which no namespace label can exempt, and Gatekeeper was still starting | Webhook limited to namespaced workload kinds; Gatekeeper moved to an earlier wave (ADR-008) |
| Gateway and routes permanently OutOfSync | The API server fills in default fields that were not in Git | Defaults written out in the manifests |
| Both tenants failed to sync | The owner email was used as a label value, which is invalid | Owner moved to an annotation |
| Quota test passed while no pod could start | The test pod broke the restricted Pod Security Standard, so 0 pods looked like "capped" | Test sample made compliant; zero now fails the test |

Not run yet locally: the recovery drill (it bootstraps from `main`, so it waits until these fixes are merged), forcing each alert, and the change lead time.
