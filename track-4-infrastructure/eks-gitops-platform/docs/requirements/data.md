# eks-gitops-platform: Data and Flows

**Status:** draft for discussion. Items marked **[assumption]** need confirmation.

## 1. What "data" means here

This project has no business data. What it handles is **desired state** (code and manifests in Git), **actual state** (Terraform state, the cluster, AWS resources), **secrets**, and **telemetry**. The platform's whole job is to receive desired state, compare it with actual state, and close the gap. Some of this data is the most sensitive in the portfolio, because it controls access to everything else.

## 2. Inputs, processing, outputs

| | What | From whom |
|---|---|---|
| **We receive** | Terraform code changes | Platform engineers, by pull request |
| | Add-on, tenant, and application manifests and Helm values | Platform engineers and tenants, by pull request |
| | Policy definitions and their tests | Security and platform engineers, by pull request |
| | Container images | Tenants' CI, pushed to an approved registry |
| | Secret values | Platform engineers or tenants, written to AWS Secrets Manager (never Git) |
| | Short-lived identity tokens | GitHub (for CI) and the EKS OIDC provider (for workloads) |
| **We process** | Plan and apply infrastructure | Terraform, run by CI |
| | Reconcile cluster against Git | ArgoCD |
| | Admit or reject each workload | Gatekeeper |
| | Create DNS records, certificates, load balancers, nodes | ExternalDNS, cert-manager, AWS Load Balancer Controller, autoscaler |
| | Copy secrets into namespaces | Secrets sync controller **[assumption: External Secrets Operator]** |
| | Collect and evaluate metrics | Prometheus |
| **We give back** | Plan output on the pull request, pass or fail checks | To the author and reviewer |
| | A running cluster matching Git | To tenants |
| | An HTTPS address per application | To tenants' users |
| | Admission decisions with the rule that failed | To the deploying tenant |
| | Sync status, dashboards, alerts | To platform engineers and tenants |
| | Spend reports and budget alerts | To the account owner |

## 3. System context

```mermaid
flowchart LR
    Dev[Engineer or tenant] -->|pull request| Git[(Git repositories)]
    Git -->|plan / apply| CI[GitHub Actions]
    CI -->|OIDC token, short-lived role| AWS[AWS APIs]
    AWS --> Infra[VPC, EKS, IAM, Route 53]
    CI -->|state read/write| State[(S3 state + lock)]
    Git -->|pulled by| Argo[ArgoCD in cluster]
    Argo -->|apply| K8s[Kubernetes API]
    K8s -->|admission check| GK[Gatekeeper]
    SM[(Secrets Manager)] -->|synced by role| K8s
    K8s --> Addons[ExternalDNS, cert-manager, load balancer controller]
    Addons --> R53[DNS records, certificates, load balancer]
    Users[Application users] -->|HTTPS| R53
    K8s --> Prom[Prometheus]
    Prom --> Graf[Grafana dashboards]
    Prom --> Alerts[Alerts]
```

## 4. Flows

### F1. Infrastructure change (Terraform)

1. An engineer opens a pull request changing Terraform code.
2. CI requests a GitHub OIDC token and exchanges it for a short-lived, read-only AWS role.
3. CI runs format, validation, security checks, and `terraform plan`, and posts the plan on the pull request.
4. A reviewer approves; the pull request merges to main.
5. CI exchanges a new token for the apply role, which trusts only the main branch, takes the state lock, and applies.
6. The new state is written to S3 as a new encrypted version; the lock is released.

**Output:** changed AWS resources, a new state version, an auditable record linking the commit to the CloudTrail entries.

### F2. Bootstrap (once per cluster)

1. After F1 creates the cluster, an engineer runs the bootstrap command through AWS Systems Manager on a small instance inside the VPC **[assumption, question 4]**. The instance acts through its own instance role, which has cluster-admin access only for the bootstrap; that access is removed afterwards and the same path is reused for break-glass access (F12).
2. Bootstrap installs ArgoCD and one root application pointing at the Git repository.
3. From here on, no command touches the cluster directly.

**Output:** ArgoCD running and syncing.

### F3. Reconciliation (continuous)

1. ArgoCD polls the repository every 3 minutes. A Git webhook is not used, because it cannot reach a private cluster without exposing ArgoCD.
2. The root application expands into add-on, tenant, and application definitions (app-of-apps).
3. ArgoCD applies them in order: namespaces and custom resource definitions first, then controllers, then policies, then tenants and their applications.
4. Each object passes through admission (F5) before it is stored.
5. ArgoCD compares live objects with Git continuously and reverts any difference.

**Output:** cluster state equal to Git; status Healthy, Synced, OutOfSync, or Degraded per application.

### F4. Tenant onboarding

1. A platform engineer or tenant opens a pull request adding one tenant definition (name, quota size, owners, allowed hostnames).
2. CI validates it against the tenant schema and the policy tests.
3. After merge, ArgoCD creates the namespace, quota, default limits, network policies, access roles, and the tenant's ArgoCD project.
4. The tenant's own application definitions, scoped to its project, start syncing.

**Output:** an isolated namespace the tenant deploys into by Git alone.

### F5. Admission

1. Any create or update request reaches the Kubernetes API.
2. Gatekeeper evaluates it against the active policies.
3. A violation is rejected with the policy name and reason, which ArgoCD shows as a sync error; rules in audit mode only report.

**Output:** only compliant workloads run.

### F6. Application exposure (HTTPS)

1. A tenant application declares a Gateway API route with a hostname under the platform domain.
2. The AWS Load Balancer Controller provisions or updates a load balancer.
3. ExternalDNS, using its own role, writes the DNS record in Route 53.
4. cert-manager, using its own role, proves domain ownership with a DNS challenge and stores the certificate as a Kubernetes secret. Rebuild drills use the Let's Encrypt staging issuer, because production issues the same certificate at most 5 times a week; only the final measured run uses production.

**Output:** a public HTTPS address with a valid certificate.

### F7. Secrets

1. A secret value is written to AWS Secrets Manager under the tenant's path.
2. Git holds only a reference: the secret's name and which namespace needs it.
3. The sync controller, using a role allowed to read only that path, copies the value into a Kubernetes secret and refreshes it on rotation.

**Output:** the application reads a normal Kubernetes secret; no value ever enters Git.

### F8. Telemetry and alerting

1. Prometheus scrapes nodes, add-ons, ArgoCD, and tenant workloads.
2. Alert rules evaluate continuously; alerts go to a notification channel **[assumption: chosen in Phase 6]**.
3. Grafana reads Prometheus for the cluster and per-tenant dashboards.
4. AWS Budgets evaluates spend daily and alerts at thresholds.

**Output:** dashboards, alerts, and cost reports.

### F9. Teardown and rebuild

1. Teardown: ArgoCD applications are deleted first so controllers remove load balancers and DNS records they created; then `terraform destroy`.
2. Rebuild: F1 recreates the infrastructure, F2 bootstraps, F3 restores everything else.

**Output:** no orphaned billable resources after teardown; a timed rebuild against NFR-1 and NFR-2.

### F10. Tenant image build and push

1. A tenant's own repository builds its container image in CI.
2. CI pushes it to GitHub Container Registry under the owner's account **[assumption]**, tagged with the commit.
3. The tenant opens a pull request changing the image tag in its application definition; after merge, F3 deploys it and F5 checks the registry is approved.

**Output:** an approved, traceable image running in the tenant's namespace.

### F11. Scheduled infrastructure drift check

1. A scheduled CI run on main exchanges a GitHub token for the read-only drift role.
2. It runs `terraform plan` against the current state.
3. Any difference raises an alert and opens an issue; nothing is applied automatically.

**Output:** infrastructure drift reported within 24 hours (NFR-5).

### F12. Break-glass human access

1. A platform engineer starts a Systems Manager session to the in-VPC instance, using their own short-lived AWS sign-in.
2. From there they run `kubectl` against the private API with a time-limited access entry.
3. The session is logged; any change they make is reverted by ArgoCD unless it is also merged to Git.

**Output:** emergency access with no SSH keys, no public endpoint, and a full audit trail.

### F13. Tenant offboarding

1. A pull request removes the tenant's definition.
2. ArgoCD deletes the tenant's applications first, so controllers remove its load balancer, DNS records, and certificates.
3. ArgoCD then deletes the namespace, quota, policies, roles, and project; the tenant's secrets in Secrets Manager are deleted separately after a recovery window.

**Output:** no leftover objects, records, or billable resources for the tenant.

## 5. Data inventory

| Data | Description | Where it lives | Written by | Read by | Sensitivity |
|---|---|---|---|---|---|
| Terraform code | Modules and environment definitions | Git | Platform engineers | CI | Low |
| Terraform state | Record of every managed resource; can contain secret values | S3, versioned and encrypted, with a lock | CI apply role | CI, platform administrators | High |
| Manifests and Helm values | Desired state of add-ons, tenants, applications | Git | Engineers, tenants | ArgoCD | Low to medium |
| Policies and tests | Gatekeeper rules | Git | Security, platform | Gatekeeper, CI | Low |
| Secrets | Database passwords, API tokens | AWS Secrets Manager, synced into the cluster | Engineers, tenants | Sync controller | High |
| Identity mappings | Which service account may assume which AWS role | Terraform and Git | Platform engineers | IAM | Medium |
| ArgoCD credentials | ArgoCD's access to the Git repositories and its own admin settings | Kubernetes secret, sourced from Secrets Manager; Git access by a read-only deploy key or GitHub App | Platform engineers | ArgoCD | High |
| Cluster state | Live Kubernetes objects | etcd, managed by EKS, encrypted | ArgoCD, controllers | Everyone with access, by role | Medium |
| Metrics | Cluster, add-on, tenant measurements | Prometheus | Prometheus | Grafana, alert rules | Low |
| Logs | Application and platform logs | Log store chosen in Phase 6 | Workloads | Engineers, tenants by namespace | Medium |
| Cloud audit trail | Every AWS API call | CloudTrail | AWS | Platform administrators | Medium |
| Cost data | Spend by tag | AWS Budgets and Cost Explorer | AWS | Account owner | Low |

## 6. Rules for sensitive data

- No secret is ever committed to Git, in plain text or base64. A secret scanner runs on every pull request.
- Terraform state is encrypted, versioned, and readable only by the CI roles and platform administrators.
- Secrets reach the cluster from AWS Secrets Manager; the cluster is never the source of truth for them.
- Each controller's AWS role can read or write only what it needs (one hosted zone, one secrets path).
- The CI apply role can be assumed only from this repository's main branch.

## 7. Volume

Small. State files are kilobytes to a few megabytes. Git repositories are a few megabytes. Metrics at local scale fit in a few GB with 15 days of retention **[assumption]**. The cost driver is running time on AWS, not data volume.

## 8. Retention

| Data | Retention |
|---|---|
| Terraform state versions | 90 days of history **[assumption]** |
| Metrics | 15 days **[assumption]** |
| Logs | 7 days locally, 30 days on AWS **[assumption]** |
| Cloud audit trail | 90 days **[assumption]** |
| Git history | Permanent |

## 9. Test data and workloads

This project has no business dataset. It needs test workloads, test inputs, and failure injection.

| Need | Choice |
|---|---|
| First tenant app, before the other projects exist | podinfo (stefanprodan/podinfo): small, exposes metrics and health checks, widely used in GitOps demos |
| Second, heavier tenant for isolation and quota tests | Online Boutique (GoogleCloudPlatform/microservices-demo), on AWS only, since it is heavy for the laptop |
| Real tenant | One of the other 7 projects in this portfolio |
| Load to drive the autoscaler and alerts | k6 against podinfo |
| Policy test cases | Gatekeeper library (open-policy-agent/gatekeeper-library) templates and their good and bad sample manifests, run with the gator CLI in CI |
| Tenancy test | Three tenants: two normal ones and a noisy neighbour that tries to exceed its quota and reach the other tenants' pods |
| Credential scanning test | AWS's documented example access key (`AKIAIOSFODNN7EXAMPLE`) planted in a test branch, which gitleaks must block |
| Terraform checks | trivy config or checkov on every pull request |
| Failure injection | Scripts: edit a managed resource with `kubectl`, break one add-on's values, delete a node, delete the cluster |
| Cost figures | AWS Pricing Calculator before the first session, Cost Explorer after each |
