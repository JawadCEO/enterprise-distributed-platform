# eks-gitops-platform

A multi-tenant Kubernetes platform on AWS EKS where Git is the only way to change anything. Terraform builds the AWS foundation; ArgoCD makes the cluster match Git and reverts manual changes; tenants are onboarded with one file; policies are enforced when a workload is deployed; a lost cluster is rebuilt from Git.

## Layout

| Path | What it holds |
|---|---|
| [docs/](docs/README.md) | Requirements, design, decisions, runbooks |
| [terraform/account](terraform/account) | State bucket, GitHub OIDC roles, budget alarms, DNS zone (kept between sessions) |
| [terraform/modules](terraform/modules) | network, cluster, workload-identity, access-host, ci-identity, budget |
| [terraform/envs](terraform/envs) | dev and prod: the same modules with different names |
| [gitops/root](gitops/root) | Helm chart rendering one ArgoCD Application per add-on, ordered by sync wave; one values file per cluster |
| [gitops/platform](gitops/platform) | Our values for each add-on, and per-cluster config (Gateway, issuers, storage, node pools) |
| [gitops/charts/tenant](gitops/charts/tenant) | Everything a tenant gets: namespace, quota, network policies, ArgoCD project, secrets, apps |
| [gitops/tenants](gitops/tenants) | One file per tenant per cluster |
| [gitops/policies](gitops/policies) | Gatekeeper templates and constraints |
| [tests](tests) | gator policy suite; live tenancy, quota and drift checks |
| [scripts](scripts) | local-up, bootstrap, sync wait, recovery drill, promotion, version/tag/cost/credential checks |

CI workflows live in the repository root under `.github/workflows/eks-gitops-*.yml`.

## Run it

- Locally, free: [docs/runbooks/local.md](docs/runbooks/local.md)
- On AWS, in a short paid session: [docs/runbooks/aws-session.md](docs/runbooks/aws-session.md)
- Upgrades, rollback, promotion, scaling: [docs/runbooks/day-2.md](docs/runbooks/day-2.md)

## Status

All 9 phases are written. Nothing has been run yet: measured numbers go in [docs/results](docs/results/README.md) after the first local run and the first AWS session.
