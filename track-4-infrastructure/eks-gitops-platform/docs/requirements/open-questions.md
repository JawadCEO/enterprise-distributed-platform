# eks-gitops-platform: Open Questions

Decisions needed before the state and configuration design. Until each is answered, the requirement documents use the working assumption shown, marked **[assumption, question N]**.

| # | Question | Working assumption |
|---|---|---|
| 1 | **What the 10-minute recovery covers.** Creating an EKS cluster alone usually takes most of 10 minutes. The target is realistic for restoring state onto a cluster and tight for a rebuild from nothing. Which is meant? | Measured from a fresh empty cluster being ready to every add-on and application healthy. A rebuild from nothing is bounded by the 15-minute provisioning target |
| 2 | **AWS budget.** Agree a spending limit before any AWS session, and set the billing alarm at that limit. | $25 per month, alarms at $12 and $25. Rough estimate of about $0.30 per running hour, to be verified against current prices |
| 3 | **Environments.** One environment, or dev and prod with promotion between them? Two doubles the running cost. | One environment on AWS. Git holds dev and prod definitions so promotion is a pull request; prod is started for one session to prove it |
| 4 | **CI access to a private cluster.** CI runs Terraform only and ArgoCD pulls changes from inside the cluster, or a CI runner inside the VPC? | CI runs Terraform only; ArgoCD pulls from inside. The one-time bootstrap runs through AWS Systems Manager via a small instance in the VPC, with no SSH keys and no public endpoint |
| 5 | **Tenancy model.** Namespaces within one cluster, or a cluster per tenant? | Namespaces within one cluster |
| 6 | **Terraform or OpenTofu.** The plan lists both; pick one. | Terraform, kept compatible with OpenTofu. The ADR notes OpenTofu's built-in state encryption as the trade-off |
| 7 | **Domain.** A domain is needed for DNS and certificates. Register a new one, or use a subdomain of one you already own? | A subdomain of a domain already owned, delegated to Route 53. Waiting on whether one exists |
