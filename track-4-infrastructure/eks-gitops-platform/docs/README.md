# eks-gitops-platform

**Status:** draft for discussion. No code yet.

## Documents

| Document |
|---|
| [Problem statement, goals, users, non-goals](requirements/problem-statement.md) |
| [Functional requirements](requirements/functional-requirements.md) |
| [Non-functional requirements and disaster recovery](requirements/non-functional-requirements.md) |
| [Data and flows: what is received, processed, and returned](requirements/data.md) |
| [Decisions still needed, with working assumptions](requirements/open-questions.md) |

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

The 9-phase framework is written for applications. This project has no database or message bus, so its phases follow how a platform is actually built: design first, then everything that can run for free on a local cluster, and only then AWS, in short sessions.

| Phase | Focus for this project | Where it runs |
|---|---|---|
| 0 | Requirements and decisions: open questions answered, ADRs on ArgoCD against Flux and Terraform against OpenTofu, budget agreed | Docs |
| 1 | Platform design: Git repository layout, app-of-apps structure, sync ordering, environments and promotion, secrets approach, tenancy model, policy set, Terraform module and state design | Docs |
| 2 | GitOps layer locally: ArgoCD bootstrap, app-of-apps, add-ons that work without AWS (cert-manager, Prometheus and Grafana, Gatekeeper), drift revert | Local cluster |
| 3 | Tenancy and policy locally: tenant onboarding by pull request, quotas, network policies, ArgoCD projects, Gatekeeper rules with CI tests | Local cluster |
| 4 | AWS foundation: billing alarm first, state backend, VPC, private EKS, IAM roles for service accounts, Route 53 zone, CI through GitHub OIDC | AWS session |
| 5 | Platform on AWS: bootstrap through Systems Manager, AWS-integrated add-ons (load balancer controller, ExternalDNS, cert-manager DNS challenge, autoscaler, Secrets Manager sync), one tenant project live over HTTPS | AWS session |
| 6 | Observability and cost: cluster and per-tenant dashboards, alerts, log store, tagging check, spend report | Local, then AWS |
| 7 | Verification and day-2 operations: timed provisioning, destroy-and-rebuild drill, drift tests, credential scan, cluster and add-on upgrade, bad-sync rollback, node scaling, dev-to-prod promotion | AWS session |
| 8 | ADRs, runbooks, results write-up | Docs |

## Laptop fit (16GB)

The GitOps layer (ArgoCD, add-ons, tenancy, policies) can be developed on a local Kubernetes cluster in about 4GB. The local cluster needs a network plugin that enforces network policies (for example k3d, or kind with Calico), or the tenancy tests in Phase 3 prove nothing. The AWS parts cannot run locally at all.

## Risks and open questions

1. **This project costs real money.** An EKS control plane, NAT gateway, and nodes bill by the hour, and ExternalDNS with cert-manager needs a registered domain. Recommendation: build and test the GitOps layer on a local cluster for free, then run on AWS in short sessions and destroy the environment each time. We should agree a budget before Phase 0 closes.
2. **AWS account and credentials are yours to set up.** I can write the Terraform and guide the steps, but I will not enter AWS credentials or keys.
3. **Private endpoint against CI access.** If the cluster API is private, GitHub-hosted runners cannot reach it. Options are a runner inside the VPC, or keeping CI limited to Terraform and letting ArgoCD pull from inside the cluster. The second is the cleaner GitOps answer.
4. **What the RTO covers.** Creating an EKS cluster alone usually takes most of 10 minutes. RTO < 10 minutes is realistic for re-syncing state onto a cluster, and tight for a rebuild from nothing. We should define which is meant.
5. **Scope of multi-tenancy.** Namespace-level isolation in one cluster, or a cluster per tenant? Recommendation: namespace-level.
6. **Environments.** One environment, or dev and prod with promotion? Two doubles the cost while running.
7. **Terraform or OpenTofu.** The plan lists both; pick one.
