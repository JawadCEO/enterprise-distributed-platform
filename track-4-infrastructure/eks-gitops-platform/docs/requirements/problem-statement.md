# eks-gitops-platform: Problem Statement

**Status:** draft for discussion. Items marked **[assumption]** need confirmation; where an assumption stands in for an open question, the question number is given.

## 1. Context

Seven other projects in this portfolio are applications. Each needs somewhere to run: a Kubernetes cluster with networking, ingress, certificates, DNS, secrets, monitoring, and guardrails. This project is that platform. It has no business logic of its own. Its product is a cluster that can be created, changed, and recreated entirely from Git, and that application teams can use safely without asking a platform engineer for anything.

## 2. Problem

**Kubernetes environments built and changed by hand are not reproducible, not auditable, and not recoverable within a predictable time.** The underlying cause is that the true state of the environment lives in the cluster and in people's memory instead of in a reviewed, versioned definition.

That one cause produces five observable failures. The last column describes the typical symptom in a hand-built setup; it is not a measurement. A real manual baseline (one console-built cluster, timed) is taken in the first AWS session.

| # | Failure | Consequence | Typical symptom |
|---|---|---|---|
| P1 | **Drift.** Untracked changes make environments differ from each other and from any written description | A change that works in one environment fails in the next; nobody can say what is actually running | No drift detection; differences found only when something breaks |
| P2 | **Slow provisioning.** A new environment needs a sequence of console steps and tickets | Days of lead time; each build is slightly different | Hours to days, not repeatable |
| P3 | **Long-lived credentials.** CI and workloads hold static cloud access keys | Keys leak, rarely rotate, and usually grant more than needed | Keys exist in CI settings and in pods |
| P4 | **Inconsistent guardrails.** Whether a privileged or unbounded workload gets in depends on the reviewer | Security and stability depend on individual attention | Policy enforced by review only |
| P5 | **Unbounded recovery.** Rebuilding a lost cluster means reconstructing everything ever installed | Recovery time is unknown until the day it is needed | Unknown; never rehearsed |

## 3. Who is affected

| Actor | What they need from the platform |
|---|---|
| Platform engineers | Change infrastructure and add-ons through reviewed code, and rebuild any environment from a commit |
| Application teams (tenants) | Deploy by merging to Git, get an HTTPS address automatically, and stay isolated from other teams |
| Security and compliance | Rules enforced by the system rather than by reviewers, and an audit trail for every change |
| CI system | Plan and apply infrastructure using short-lived identity, with no stored keys |

## 4. Proposed approach (one paragraph)

Terraform defines the cloud foundation: network, cluster, identity, DNS zone, and its own state backend. After the cluster exists, a single bootstrap installs ArgoCD and points it at a root application in Git. From then on, Git is the only way to change the cluster: ArgoCD continuously makes the cluster match Git and reverts anything changed by hand. Workloads and CI obtain cloud access through short-lived, federated identity. An admission controller rejects workloads that break policy. Recovery is a rebuild: Terraform recreates the foundation and ArgoCD restores everything else.

## 5. Goals and success criteria

Each goal maps to a problem above and to a number that can be measured in Phase 7.

| Goal | Addresses | Success criterion | How it is measured |
|---|---|---|---|
| G1 Reproducible environments | P1, P2 | Two builds from the same commit produce the same resource set; drift is detected within 24 hours and cluster drift is reverted within 5 minutes | Compare plan output after rebuild; inject a manual change and time the revert |
| G2 Fast provisioning | P2 | Empty region (state backend and DNS zone already present) to a working cluster with all add-ons healthy in **< 15 minutes** | Timed CI run, 3 runs, report each, with time per stage: network, control plane, nodes, add-ons, certificates. EKS control plane creation alone takes about 10 minutes, so the stage breakdown explains the result even if the target is missed |
| G3 Bounded recovery | P5 | Fresh empty cluster to every add-on and application healthy in **< 10 minutes** **[assumption, question 1]** | Destroy-and-rebuild drill, timed from cluster ready to all ArgoCD apps Healthy and Synced |
| G4 No static credentials | P3 | Zero long-lived cloud keys in repositories, CI settings, cluster secrets, or laptops | Secret scanner on the repositories, review of CI secrets, scan of cluster secrets |
| G5 Self-service tenancy | P4 | A new team is onboarded by one merged pull request, with no manual step | Onboard a tenant and count manual actions (target 0) |
| G6 Enforced guardrails | P4 | 100% of non-compliant test workloads rejected at admission; 0 compliant ones rejected | Policy test suite in CI plus a live admission test |
| G7 Controlled cost | All | Spend stays under **$25 per month**, alarm before the limit **[assumption, question 2]** | AWS Budgets report |
| G8 Tenant self-visibility | P4 | A tenant sees its own sync status, health, logs, and dashboards without asking the platform team, and cannot see other tenants' | Log in as a tenant and check what is visible |

## 6. Scope

**In scope:** one AWS region, one EKS cluster per environment, Terraform modules, ArgoCD with an app-of-apps layout, platform add-ons (AWS Load Balancer Controller with Gateway API, cert-manager, ExternalDNS, EBS storage driver, Prometheus and Grafana, Gatekeeper, node autoscaler, secrets sync), namespace tenancy, CI pipelines with OIDC, a recovery drill, and one of the other projects deployed as a tenant.

## 7. Non-goals

| Non-goal | Why |
|---|---|
| More than one cloud provider | Doubles the work without adding a new concept to demonstrate |
| A service mesh | Adds operational weight; mTLS and traffic shaping are not required by any tenant |
| Managed databases and other non-Kubernetes AWS services | Beyond what the add-ons need; tenants run their own dependencies in-cluster |
| Multi-region clusters | Cost and complexity; recovery is demonstrated by rebuild in the same region |
| Windows nodes | No tenant needs them |
| A cluster per tenant | Namespace isolation is chosen **[assumption, question 5]** |
| Building the tenant applications | The other projects are the tenants |
| Production-grade availability guarantees | Environments run only during short sessions and are destroyed afterwards |

## 8. Constraints

| Constraint | Effect on the design |
|---|---|
| All local work happens on a 16GB laptop | The GitOps layer is built and tested on a local Kubernetes cluster (k3d, or kind with Calico, 6GB or less); AWS runs only in short sessions |
| AWS bills by the hour | Every AWS session ends in a full destroy; a billing alarm exists before the first apply |
| The private API endpoint blocks outside access | CI cannot reach the cluster API; ArgoCD pulls from inside; a one-time bootstrap path is needed **[assumption, question 4]** |
| A registered domain is required for DNS and certificates | Domain choice is open **[question 7]** |
| AWS credentials are set up by the account owner | The code and runbook never contain or request keys |

## 9. Working assumptions

These stand in for open decisions until they are answered (see [open-questions.md](open-questions.md)).

| # | Assumption |
|---|---|
| A1 | Recovery target means restoring state onto a fresh empty cluster; a rebuild from nothing is bounded by the 15-minute provisioning target (question 1) |
| A2 | Budget is $25 per month, with alarms at $12 and $25 (question 2) |
| A3 | One environment runs on AWS; Git holds dev and prod definitions, and prod is started for one session to prove promotion (question 3) |
| A4 | CI runs Terraform only; ArgoCD pulls from inside the cluster; the one-time bootstrap goes through AWS Systems Manager via a small instance in the VPC (question 4) |
| A5 | Tenants are isolated by namespace in a shared cluster (question 5) |
| A6 | Terraform is used, kept compatible with OpenTofu (question 6) |
| A7 | No domain until one is named; the platform uses internal hostnames and its own CA (question 7, ADR-013) |
