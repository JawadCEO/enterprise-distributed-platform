# eks-gitops-platform: Non-Functional Requirements

**Status:** draft for discussion. Items marked **[assumption]** need confirmation.

Every target names how it will be measured, so Phase 7 can report a number against it. Targets are for AWS sessions unless marked local. All local runs happen on a 16GB laptop; larger figures are projections, not measurements.

## 1. Performance and timing

| ID | Attribute | Target | Measured as |
|---|---|---|---|
| NFR-1 | Provisioning time | < 15 minutes | From `terraform apply` on an empty region (state backend and DNS zone already present) to every ArgoCD application Healthy and Synced. 3 timed runs, all reported |
| NFR-2 | Recovery time (RTO) | < 10 minutes **[assumption, question 1]** | From a fresh empty cluster being ready to every ArgoCD application Healthy and Synced and the tenant's HTTPS address answering. Drills use the Let's Encrypt staging issuer to stay inside its rate limits |
| NFR-3 | Recovery point (RPO) | 0 for platform configuration | Everything is in Git; the last merged commit is restored. Tenant data stored inside the cluster is out of scope (tenants own it) |
| NFR-4 | Change lead time | < 5 minutes **[assumption]** | From pull request merge to the change running in the cluster. Relies on ArgoCD polling Git every 3 minutes (its default), because a private cluster cannot receive a Git webhook without exposing ArgoCD |
| NFR-5 | Drift correction | Cluster drift reverted < 5 minutes; infrastructure drift reported < 24 hours | Inject a manual change and time it; scheduled plan run |
| NFR-6 | Local footprint | Whole GitOps layer runs in ≤ 6GB of memory (local), with Prometheus retention and replicas reduced locally | Memory used by the local cluster with all add-ons, measured |

## 2. Security

| ID | Attribute | Target | Measured as |
|---|---|---|---|
| NFR-7 | Static credentials | Zero | Secret scanner over repositories and history; review of CI secrets; scan of cluster secrets for AWS keys |
| NFR-8 | API exposure | Kubernetes API not reachable from the internet | Connection attempt from outside the VPC fails |
| NFR-9 | Least privilege | Each role grants only what its user needs; the CI apply role is assumable only from this repository's main branch | IAM policy review; attempt to assume the role from another branch fails |
| NFR-10 | Policy coverage | 100% of tenant namespaces under admission policy | No tenant namespace is exempt from Gatekeeper |
| NFR-11 | Encryption | Terraform state, Kubernetes secrets, and node volumes encrypted at rest; all ingress over TLS | Configuration check; plain HTTP redirects to HTTPS |
| NFR-12 | Auditability | Every infrastructure and cluster change traces to a commit and an approver | Git history plus CloudTrail; no change path bypasses Git except the documented bootstrap |
| NFR-13 | Supply chain | Add-on chart and image versions pinned; images only from approved registries | Policy FR-20 plus a check for unpinned versions in CI |

## 3. Reliability

| ID | Attribute | Target | Measured as |
|---|---|---|---|
| NFR-14 | Reproducibility | Two builds from the same commit produce the same resources | Plan after rebuild shows no changes |
| NFR-15 | Fault isolation | One failing add-on or tenant does not block the others | Break one add-on deliberately; others stay Synced |
| NFR-16 | Zonal spread | Nodes in at least 2 availability zones | Node labels |
| NFR-17 | Idempotence | Re-running provisioning or bootstrap on a working environment changes nothing | Second run reports no changes |
| NFR-26 | Admission availability | Gatekeeper runs 2 replicas; what happens when it is down (block every deploy, or let everything through) is decided and documented in an ADR | Stop Gatekeeper and observe the documented behaviour |

Availability targets (uptime percentages) are deliberately not set: environments exist only during sessions.

## 4. Operability and maintainability

| ID | Attribute | Target | Measured as |
|---|---|---|---|
| NFR-18 | Observability | Every add-on and tenant has metrics and alerts (FR-22, FR-23) | Each alert proven by a forced test |
| NFR-19 | Upgradability | A cluster version upgrade and an add-on upgrade are each a pull request with a documented rollback | Performed once in Phase 7 |
| NFR-20 | Modularity | Terraform modules are reusable across environments with no copy-paste; environments differ only in variables | Dev and prod use the same modules |
| NFR-21 | Documentation | Runbooks for provision, bootstrap, teardown, rebuild, tenant onboarding, and bad-sync rollback | Each runbook executed once as written |

## 5. Cost

| ID | Attribute | Target | Measured as |
|---|---|---|---|
| NFR-22 | Monthly spend | ≤ $25 **[assumption, question 2]** | AWS Budgets |
| NFR-23 | Session cost | About $0.30 to $0.35 per running hour **[assumption, estimated from list prices]**: EKS control plane, one NAT gateway (a deliberate cost decision), two small nodes, one load balancer, and public IPv4 addresses at $0.005 per address per hour | Cost Explorer after each session |
| NFR-24 | Idle cost | ≤ $3 per month between sessions: DNS zone ($0.50), stored secrets ($0.40 each), and the state bucket. Encryption uses AWS-managed keys, since a customer-managed key adds $1 per month | Cost Explorer for a day with no session |
| NFR-25 | Cost visibility | 100% of resources tagged by environment, owner, and project | Tag compliance check |

## 6. Disaster recovery

| Failure | Detection | Expected behaviour | Target |
|---|---|---|---|
| Manual change in the cluster | ArgoCD shows OutOfSync | ArgoCD reverts it to match Git | < 5 minutes |
| Bad change merged | Sync fails or app turns Degraded; alert fires | Revert the commit; ArgoCD rolls the cluster back | < 5 minutes after the revert merges |
| Add-on failure | ArgoCD reports Degraded; alert fires | Other add-ons unaffected; fix by pull request | Isolation only |
| Node or zone lost | Node not ready alert | Workloads reschedule to the surviving zone; autoscaler replaces capacity. With one NAT gateway, losing its zone also cuts outbound internet access for the other zone until the NAT is recreated; accepted for cost | Compute self-heals; egress does not |
| Cluster lost | Cluster unreachable | Terraform recreates it; bootstrap; ArgoCD restores every add-on and application | NFR-2 |
| Terraform state lost or corrupted | Plan fails or shows unexpected changes | Restore the previous object version from the versioned bucket | < 15 minutes |
| Git hosting unavailable | ArgoCD cannot fetch | Cluster keeps running its last synced state; no changes possible until Git returns | No outage of running workloads |
| Secrets Manager unavailable | Sync controller errors | Existing Kubernetes secrets stay in place; new secrets wait | No outage of running workloads |
