# eks-gitops-platform: Architecture Decisions

**Status:** draft for discussion. Decisions that rest on an unanswered open question say so.

## ADR-001: GitOps engine

| Option | For | Against |
|---|---|---|
| **ArgoCD, self-managed** | UI that shows sync and health per application; AppProjects give per-tenant limits; app-of-apps and ApplicationSets are well known | Runs on our nodes and must be bootstrapped once |
| Flux | Lighter; everything is a Kubernetes object; no UI to secure | No built-in UI for tenants; multi-tenancy is assembled from service accounts |
| EKS managed Argo CD capability | AWS runs and patches it outside our nodes; no bootstrap through the private endpoint | Needs IAM Identity Center; hides the install, RBAC, and bootstrap work this project exists to show; does not run locally |

**Decision: self-managed ArgoCD.** Tenant visibility (G8) needs a UI scoped by project, and the same install runs on the local cluster. The managed capability is the production recommendation and is noted in the write-up.

## ADR-002: Infrastructure as code tool

| Option | For | Against |
|---|---|---|
| **Terraform (≥ 1.10)** | What job descriptions ask for; largest module ecosystem; S3 native state locking without DynamoDB | Business Source licence |
| OpenTofu | Open licence; built-in state encryption | Less often asked for |

**Decision: Terraform, written to run unchanged on OpenTofu** (no Terraform-only features). Rests on open question 6.

## ADR-003: Terraform module strategy

| Option | For | Against |
|---|---|---|
| **Thin in-house modules wrapping the community `terraform-aws-modules` VPC and EKS modules** | Battle-tested internals; our modules expose only the inputs this platform needs | Some indirection |
| Everything written from raw resources | Shows every resource | Hundreds of lines for solved problems; more bugs |
| Community modules called directly from each environment | Least code | Environments repeat large input blocks; violates NFR-20 |

**Decision: thin in-house modules.** The interesting decisions (private endpoint, access entries, identity, tags) live in our code; subnet maths does not.

## ADR-004: Workload identity

| Option | For | Against |
|---|---|---|
| IRSA (OIDC provider per cluster) | Works everywhere, including non-EKS clusters; well known | Role trust policies embed the cluster's OIDC ID, which changes on every rebuild |
| **EKS Pod Identity** | AWS's current recommendation; trust policy is the same for every cluster; association is one Terraform resource | EKS only; needs the Pod Identity agent add-on |

**Decision: EKS Pod Identity for workloads.** Every add-on that calls AWS (ExternalDNS, cert-manager, Load Balancer Controller, EBS driver, Karpenter, External Secrets) supports it, and rebuilds (FR-27) no longer rewrite trust policies. GitHub Actions still uses OIDC federation (FR-15). The original brief said IRSA; this is a change worth confirming.

## ADR-005: Gateway API implementation

| Option | For | Against |
|---|---|---|
| **Envoy Gateway, behind an NLB created by the AWS Load Balancer Controller** | Same Gateway and route manifests on the laptop and on AWS; one load balancer for all tenants | One more controller to run |
| AWS Load Balancer Controller native Gateway API (ALB) | No in-cluster proxy | Does not run locally, so tenant manifests would differ per environment; newer feature |
| ingress-nginx | Familiar | Retired by the Kubernetes project in March 2026; no security fixes |

**Decision: Envoy Gateway everywhere; on AWS its service is exposed by an NLB from the Load Balancer Controller.** Identical tenant manifests across environments matter more than saving one proxy.

## ADR-006: Node autoscaler

| Option | For | Against |
|---|---|---|
| **Karpenter, with a small managed node group for system pods** | Picks instance types per workload; fast scale-up; current EKS default choice | More IAM setup; Karpenter itself needs fixed capacity to run on |
| Cluster Autoscaler | Simple with managed node groups | Slower; tied to node group sizes |

**Decision: Karpenter.** A 2-node managed group runs ArgoCD and the add-ons; Karpenter adds nodes for tenant load (FR-12).

## ADR-007: App-of-apps structure

| Option | For | Against |
|---|---|---|
| **A root Helm chart that renders one ArgoCD Application per add-on from a per-cluster values file** | Sync waves order the add-ons; promotion is a version change in one values file; enabling an add-on per cluster is one flag | Needs ArgoCD's health check for Application resources switched on |
| ApplicationSets for add-ons | Less templating | Waves do not order generated applications (progressive sync is still alpha) |
| Kustomize overlays per cluster | No templating language | Version changes per cluster become patches, which are harder to review |

**Decision: a root Helm chart for platform add-ons; an ApplicationSet for tenants**, since tenants have no ordering between them and one file per tenant is the onboarding unit (FR-17).

## ADR-008: Admission controller failure mode

| Option | For | Against |
|---|---|---|
| Fail open everywhere (Gatekeeper's default) | Gatekeeper outage never blocks deploys | Non-compliant workloads get in during the outage |
| **Fail closed for tenant namespaces only; platform namespaces exempt** | Tenant guardrails always hold; the platform can still repair itself, including Gatekeeper | A Gatekeeper outage blocks tenant deploys |
| Fail closed everywhere | Strictest | Gatekeeper cannot be reinstalled while it is down |

**Decision: fail closed for tenant namespaces, with 2 Gatekeeper replicas and a disruption budget** (NFR-26). The audit controller reports anything that predates a rule.

## ADR-009: Secrets delivery

| Option | For | Against |
|---|---|---|
| **External Secrets Operator with AWS Secrets Manager** | Secrets Manager stays the source of truth; per-tenant store limited to a path; a fake provider exists for local testing | One more controller |
| Sealed Secrets | No cloud dependency | Encrypted secrets live in Git, and the cluster key becomes something to back up |
| Secrets Store CSI driver | Mounts secrets as files | Applications must read files; syncing to Kubernetes secrets is a secondary feature |

**Decision: External Secrets Operator.** Each tenant gets a SecretStore whose AWS role can read only `platform/<env>/<tenant>/*`.

## ADR-010: Terraform state backend

| Option | For | Against |
|---|---|---|
| **S3 with native lock file (`use_lockfile`), versioning, SSE-S3 encryption** | No DynamoDB table; versioned history for recovery; no customer-managed key cost | Needs Terraform ≥ 1.10 |
| S3 plus DynamoDB lock table | Works on old versions | DynamoDB locking is deprecated in recent Terraform |
| HCP Terraform | Managed runs and locking | Another account; state leaves AWS |

**Decision: S3 with native locking.** The bucket is created once by a small separate configuration and is never destroyed with the environment (NFR-24).

## ADR-011: Kubernetes version policy

**Decision: run only versions in EKS standard support.** A cluster on extended support costs $0.60 an hour instead of $0.10, which would break the budget. The version is one variable; it is checked against the EKS release calendar before every first apply of a session.

## ADR-012: Log store

| Option | For | Against |
|---|---|---|
| **Loki (single binary, filesystem) with Grafana Alloy** | Fits the laptop budget; queried in the same Grafana as metrics; Alloy reads logs through the Kubernetes API, so no host mounts | Single replica; not for heavy query load |
| OpenSearch | Full-text search | Several GB of memory; breaks NFR-6 |
| CloudWatch Logs | Nothing to run on AWS | Does not run locally; per-GB cost |

**Decision: Loki with Alloy, in the monitoring namespace.** Promtail is not used because it reached end of life. Tenants read their own pod logs through their ArgoCD project (FR-29), so Loki is for platform engineers.

## ADR-013: Running without a domain

Open question 7 is unanswered, so the platform must work without a registered domain and without spending on one.

| Option | For | Against |
|---|---|---|
| **No public DNS: internal hostnames (`*.dev.eks-gitops.internal`) and the platform's own CA, reached with `curl --resolve` against the load balancer** | Free; every other part of the platform (Gateway, certificates, policies, tenancy) is unchanged | No browser-trusted certificate; ExternalDNS and Let's Encrypt are not exercised |
| Register a cheap domain in Route 53 | Exercises the full DNS and certificate flow | Costs money; the owner's decision |
| Wildcard DNS services such as sslip.io | No registration | Load balancer IPs change; certificates per hostname; shared rate limits |

**Decision: no-domain mode by default.** Setting `domain` in Terraform, enabling ExternalDNS in `values-dev.yaml`, and switching the Gateway's issuer annotation to `letsencrypt-staging` turns on the full flow; the Let's Encrypt issuers are kept as `letsencrypt-issuers.yaml.domain-mode` in each cluster's config.
