# eks-gitops-platform: Results

**Status:** nothing measured yet. Every row below is filled from a real run; until then it says "not run". Local figures come from the 16GB laptop; AWS figures from a dev session. Raw timings are appended to [measurements.txt](measurements.txt) by the scripts.

## Targets

| Target | Requirement | How measured | Local | AWS |
|---|---|---|---|---|
| Provisioning < 15 min | NFR-1, G2 | `eks-gitops terraform` apply job summary (time per stage) plus `scripts/bootstrap.sh` time; 3 runs | n/a | not run |
| Recovery < 10 min | NFR-2, G3 | `scripts/recovery-drill.sh` | not run | not run |
| Change lead time < 5 min | NFR-4 | Merge time to new version running | not run | not run |
| Cluster drift reverted < 5 min | NFR-5 | `tests/tenancy/run.sh` drift check | not run | not run |
| Infrastructure drift reported < 24 h | NFR-5 | `eks-gitops drift` workflow after a console change | n/a | not run |
| Local memory ≤ 6GB | NFR-6 | `docker stats` with all add-ons | not run | n/a |
| Zero static credentials | NFR-7, G4 | `scripts/scan-credentials.sh`, `scripts/test-secret-scanner.sh` | not run | not run |
| API not reachable from the internet | NFR-8 | Connection attempt from outside the VPC | n/a | not run |
| Policies: bad rejected, good admitted | FR-20, G6 | `gator verify tests/policies/` and live dry runs | not run | not run |
| Tenant isolation and quota | FR-18, G5 | `tests/tenancy/run.sh` | not run | not run |
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
