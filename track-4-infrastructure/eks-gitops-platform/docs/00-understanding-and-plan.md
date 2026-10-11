# eks-gitops-platform: Understanding and Plan

**Status:** draft for discussion. No code yet.

## Brief

| Item | Value |
|---|---|
| Domain | Multi-tenant cloud and Kubernetes GitOps platform: cluster provisioning, ingress, automated application delivery, and observability |
| Stack | Terraform / OpenTofu, AWS EKS, ArgoCD, Helm, cert-manager, ExternalDNS, Prometheus / Grafana, GitHub Actions |
| Provisioning target | Complete environment in < 15 minutes |
| Recovery target | RTO < 10 minutes, rebuilding cluster state from Git via ArgoCD |
| Security | No static AWS credentials (IRSA with OIDC), private cluster endpoints, OPA Gatekeeper policies |

## My understanding

This project has no application of its own. It is the platform the other projects run on. Terraform creates the AWS foundation: network, cluster, and identity. After that, Git is the single source of truth. ArgoCD watches a repository and makes the cluster match it, so installing an add-on or deploying an application is a pull request and never a manual command. Teams get isolated namespaces with quotas and policies that are enforced automatically. If the cluster is lost, it is recreated and ArgoCD restores everything from Git.

## What this project demonstrates

- Infrastructure as code with reusable modules and remote state
- GitOps delivery with an app-of-apps structure
- Identity without long-lived keys, for both CI and workloads
- Multi-tenancy: isolation, quotas, network policy
- Policy enforced at admission time
- Disaster recovery from Git

## Preliminary shape (for discussion)

This project has components, not microservices.

| Component | Responsibility |
|---|---|
| Terraform modules | VPC, EKS, node groups, IAM and IRSA, DNS zone, state backend |
| Bootstrap | Installs ArgoCD and points it at the root application |
| Platform add-ons | Ingress controller, cert-manager, ExternalDNS, Prometheus and Grafana, Gatekeeper |
| Tenancy | Namespaces, quotas, network policies, ArgoCD projects per tenant |
| CI pipelines | Plan and apply through GitHub OIDC, policy and security checks on pull requests |
| Tenant workload | One of the other 7 projects deployed as proof |

## Phase plan

The 9-phase framework is written for applications, so several phases are adapted.

| Phase | Focus for this project |
|---|---|
| 0 | Requirements, non-goals, ADR on GitOps toolchain (ArgoCD against Flux) |
| 1 | Adapted: no database. Covers Terraform state and locking, Git repository layout as source of truth, secrets management |
| 2 | Adapted: no message bus. Covers the delivery flow: sync ordering, app-of-apps, promotion between environments |
| 3 | Adapted: platform add-ons and the tenancy model |
| 4 | IRSA, GitHub OIDC, private endpoint access, Gatekeeper policies, network policy |
| 5 | Cluster and add-on monitoring, alerting, AWS cost model and tagging |
| 6 | Policy tests, provisioning-time measurement, destroy-and-rebuild recovery drill |
| 7 | Cluster and add-on upgrades, node scaling, rollback of a bad sync |
| 8 | ADRs, runbook, results write-up |

## Laptop fit (16GB)

The GitOps layer (ArgoCD, add-ons, tenancy, policies) can be developed on a local Kubernetes cluster in about 4GB. The AWS parts cannot run locally at all.

## Risks and open questions

1. **This project costs real money.** An EKS control plane, NAT gateway, and nodes bill by the hour, and ExternalDNS with cert-manager needs a registered domain. Recommendation: build and test the GitOps layer on a local cluster for free, then run on AWS in short sessions and destroy the environment each time. We should agree a budget before Phase 0 closes.
2. **AWS account and credentials are yours to set up.** I can write the Terraform and guide the steps, but I will not enter AWS credentials or keys.
3. **Private endpoint against CI access.** If the cluster API is private, GitHub-hosted runners cannot reach it. Options are a runner inside the VPC, or keeping CI limited to Terraform and letting ArgoCD pull from inside the cluster. The second is the cleaner GitOps answer.
4. **What the RTO covers.** Creating an EKS cluster alone usually takes most of 10 minutes. RTO < 10 minutes is realistic for re-syncing state onto a cluster, and tight for a rebuild from nothing. We should define which is meant.
5. **Scope of multi-tenancy.** Namespace-level isolation in one cluster, or a cluster per tenant? Recommendation: namespace-level.
6. **Environments.** One environment, or dev and prod with promotion? Two doubles the cost while running.
7. **Terraform or OpenTofu.** The plan lists both; pick one.
