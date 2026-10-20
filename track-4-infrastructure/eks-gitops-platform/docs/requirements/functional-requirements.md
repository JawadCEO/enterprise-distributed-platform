# eks-gitops-platform: Functional Requirements

**Status:** draft for discussion. Items marked **[assumption]** need confirmation.

Each requirement states what the system shall do and how we will know it does it. Priority: **Must** is needed for the project to meet its goals; **Should** strengthens it and can slip to a later phase.

## Capability map

| Area | Requirements | Goal served |
|---|---|---|
| A. Cloud foundation | FR-1 to FR-4 | G1, G2 |
| B. Bootstrap and GitOps delivery | FR-5 to FR-9 | G1, G3 |
| C. Platform add-ons | FR-10 to FR-13 | G2 |
| D. Identity and secrets | FR-14 to FR-16 | G4 |
| E. Tenancy | FR-17 to FR-19 | G5 |
| F. Policy | FR-20 to FR-21 | G6 |
| G. Observability and cost | FR-22 to FR-24 | G7 |
| H. Lifecycle and recovery | FR-25 to FR-27 | G1, G3 |

## A. Cloud foundation

| ID | Requirement | Acceptance criteria | Priority |
|---|---|---|---|
| FR-1 | The system shall provision a VPC with public and private subnets across at least 2 availability zones, from reusable Terraform modules | `terraform apply` creates the VPC; nodes run only in private subnets; load balancers only in public subnets | Must |
| FR-2 | The system shall provision an EKS cluster with managed node groups and a private-only API endpoint | The API endpoint has no public access; nodes join the cluster; Kubernetes version is pinned in code | Must |
| FR-3 | The system shall store Terraform state remotely with locking, encryption, and versioning | Two concurrent applies cannot both proceed; state objects are encrypted and versioned; the state backend is itself created by code | Must |
| FR-4 | The system shall create a public DNS zone for the platform domain | Records created under the zone resolve publicly **[assumption: delegated subdomain, question 7]** | Must |

## B. Bootstrap and GitOps delivery

| ID | Requirement | Acceptance criteria | Priority |
|---|---|---|---|
| FR-5 | The system shall install ArgoCD and the root application with one bootstrap command | After one command, ArgoCD runs and begins syncing the root application with no further manual step **[assumption: run over Systems Manager, question 4]** | Must |
| FR-6 | The system shall declare every add-on, tenant, and application in Git using an app-of-apps structure | Removing an application from Git removes it from the cluster; nothing in the cluster is unowned by an ArgoCD application except the bootstrap itself | Must |
| FR-7 | The system shall apply changes in a defined order, so that dependencies such as custom resource definitions exist before what uses them | A clean cluster reaches Healthy and Synced in one pass with no manual retries | Must |
| FR-8 | The system shall revert manual changes made in the cluster | A change made with `kubectl` to a managed resource is reverted automatically | Must |
| FR-9 | The system shall promote a change from dev to prod through a pull request | Prod definitions change only by a merged pull request; the same version runs in both after promotion **[assumption: prod started for one session, question 3]** | Should |

## C. Platform add-ons

| ID | Requirement | Acceptance criteria | Priority |
|---|---|---|---|
| FR-10 | The system shall install from Git: an ingress controller, cert-manager, ExternalDNS, Prometheus and Grafana, Gatekeeper, a node autoscaler, and a secrets sync controller | Each add-on is an ArgoCD application with a pinned version; all report Healthy | Must |
| FR-11 | The system shall give each tenant application an HTTPS address with a DNS record and a valid certificate, created automatically | Declaring an ingress for a hostname produces a resolving DNS record and a trusted certificate with no manual step | Must |
| FR-12 | The system shall add and remove nodes in response to pending workloads | Scaling a workload beyond capacity adds a node; removing it lets the node scale down | Should |
| FR-13 | The system shall isolate add-on failures | One add-on failing does not stop the others from syncing; the failure shows as Degraded in ArgoCD | Must |

## D. Identity and secrets

| ID | Requirement | Acceptance criteria | Priority |
|---|---|---|---|
| FR-14 | The system shall let workloads access AWS through IAM roles bound to Kubernetes service accounts, with no keys | Add-ons that call AWS (ExternalDNS, cert-manager, autoscaler, secrets sync, load balancer controller) use role-bound service accounts; no AWS key exists in the cluster | Must |
| FR-15 | The system shall let CI access AWS through GitHub's short-lived identity tokens; plan on pull request, apply on merge after approval | The plan is posted on the pull request; apply runs only from the main branch after approval; the CI role trusts only this repository's main branch for apply | Must |
| FR-16 | The system shall deliver application secrets from AWS Secrets Manager into the cluster | A secret created in Secrets Manager appears as a Kubernetes secret in the right namespace; no secret value exists in Git **[assumption: External Secrets Operator]** | Must |

## E. Tenancy

| ID | Requirement | Acceptance criteria | Priority |
|---|---|---|---|
| FR-17 | The system shall onboard a tenant through one pull request that creates: namespace, resource quota, default limits, network policies, access roles, and an ArgoCD project | Merging one file or folder per tenant produces all six objects; no manual step **[assumption: namespace tenancy, question 5]** | Must |
| FR-18 | The system shall isolate tenants from each other | A pod in tenant A cannot reach a pod in tenant B; tenant A's ArgoCD project cannot deploy into tenant B's namespace or create cluster-wide resources | Must |
| FR-19 | The system shall offboard a tenant by removing its definition from Git | All the tenant's objects are removed; DNS records and certificates for its hostnames are cleaned up | Should |

## F. Policy

| ID | Requirement | Acceptance criteria | Priority |
|---|---|---|---|
| FR-20 | The system shall reject, at admission, workloads that: run privileged, lack resource requests and limits, use images outside approved registries, or lack required labels | Each rule has a failing and a passing example; the failing ones are rejected with a message naming the rule | Must |
| FR-21 | The system shall test policies in CI before they are enforced, and support an audit-only mode for new rules | A pull request that breaks a policy test fails; a new rule can run in audit mode and report violations without blocking | Must |

## G. Observability and cost

| ID | Requirement | Acceptance criteria | Priority |
|---|---|---|---|
| FR-22 | The system shall provide a cluster dashboard and a per-tenant dashboard | Dashboards are provisioned from Git; per-tenant views filter by namespace | Must |
| FR-23 | The system shall alert on: node not ready, add-on Degraded, ArgoCD sync failing, certificate expiring within 14 days, tenant near quota | Each alert fires in a test where its condition is forced | Must |
| FR-24 | The system shall tag every AWS resource with environment, owner, and project, and alert when spend crosses budget thresholds | A check finds zero untagged resources; a budget alarm exists before the first cluster apply **[assumption: $12 and $25, question 2]** | Must |

## H. Lifecycle and recovery

| ID | Requirement | Acceptance criteria | Priority |
|---|---|---|---|
| FR-25 | The system shall detect drift in Terraform-managed infrastructure on a schedule | A scheduled plan reports any difference from code; a forced manual change is reported within 24 hours | Should |
| FR-26 | The system shall tear down an environment completely with one documented command | After teardown, no billable resource remains except the state backend and DNS zone | Must |
| FR-27 | The system shall rebuild a lost environment from Git with a documented, rehearsed procedure | The rebuild drill restores all add-ons and the tenant application; the time is recorded against the recovery target | Must |

## Traceability from the original list

| Original | Now |
|---|---|
| FR-1 to FR-4 | FR-1, FR-2, FR-3, FR-5 |
| FR-5 | FR-10, FR-12 |
| FR-6 | FR-17 |
| FR-7 | FR-11 |
| FR-8, FR-9 | FR-14, FR-15 |
| FR-10, FR-11 | FR-20, FR-21 |
| FR-12 | FR-8, FR-25 |
| FR-13 | FR-22, FR-23 |
| FR-14 | FR-26, FR-27 |
| FR-15 | FR-24 |
| New | FR-4, FR-6, FR-7, FR-9, FR-13, FR-16, FR-18, FR-19 |
