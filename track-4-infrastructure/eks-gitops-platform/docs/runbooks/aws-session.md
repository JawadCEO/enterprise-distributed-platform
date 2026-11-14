# Runbook: an AWS session

Every session starts from nothing and ends with a full destroy (FR-26). The account owner signs in with their own short-lived AWS credentials (for example `aws sso login`); no access keys are created at any step.

## Once per account

1. Without a domain, skip this step: the platform runs with internal hostnames and its own CA (ADR-013). With one, set `domain` in `terraform/account/variables.tf` and `terraform/envs/*/variables.tf`, enable `external-dns` in `gitops/root/values-dev.yaml` and `values-prod.yaml`, set the domain in `gitops/platform/external-dns/values-*.yaml`, `gitops/root/values-*.yaml` (`appsDomain`) and `gitops/platform/cluster-config/*/gateway.yaml`, rename `letsencrypt-issuers.yaml.domain-mode` to `.yaml`, and set the Gateway issuer to `letsencrypt-staging`.
2. Set `awsAccountId` in `gitops/root/values-dev.yaml` and `values-prod.yaml`.
3. Create the account resources:
   ```bash
   cd terraform/account
   terraform init
   terraform apply -var budget_email=you@example.com
   ```
4. With a domain only: add the `zone_name_servers` output as NS records at its registrar.
5. Move this stack's state into the new bucket: add a `backend "s3"` block (key `account/terraform.tfstate`, `use_lockfile = true`), then run `terraform init -migrate-state -backend-config="bucket=<state_bucket output>"`.
6. In the GitHub repository settings, add variables `EKS_GITOPS_ROLE_PLAN`, `EKS_GITOPS_ROLE_APPLY`, `EKS_GITOPS_ROLE_DRIFT` (from `ci_role_arns`) and `EKS_GITOPS_STATE_BUCKET`. Create environments `aws-dev` and `aws-prod`, each with yourself as required reviewer.

## Start a session

1. Check that `kubernetes_version` in `terraform/envs/dev/variables.tf` is in EKS standard support (ADR-011).
2. Run the `eks-gitops terraform` workflow with environment `dev`, action `apply`, and approve the job. Note the time it reports.
3. Bootstrap from the access host:
   ```bash
   aws ssm start-session --target <access_host_instance_id output>
   sudo -iu ssm-user
   git clone https://github.com/JawadCEO/enterprise-distributed-platform.git
   cd enterprise-distributed-platform/track-4-infrastructure/eks-gitops-platform
   aws eks update-kubeconfig --name eks-gitops-dev --region us-east-1
   scripts/bootstrap.sh dev
   ```
4. Set `access_host_admin = false` by pull request once the bootstrap is done; keep it only for break-glass.

## End a session

1. Delete the ArgoCD Applications first, so controllers remove their load balancer and DNS records (flow F9):
   ```bash
   kubectl -n argocd delete application root
   ```
   Wait until no load balancer remains in the EC2 console.
2. Run the `eks-gitops terraform` workflow with action `destroy`.
3. Next day, check Cost Explorer shows only the idle items (NFR-24).

## Recovery drill (FR-27, NFR-2)

Destroy and recreate the environment as above. Right after the apply, run `scripts/recovery-drill.sh dev` on the access host instead of `bootstrap.sh`: it refuses to run on a cluster that already has ArgoCD, then times the restore from Git. The rebuild-from-nothing time is the apply time plus that number. With a domain, drills use the Let's Encrypt staging issuer, and production only for the final measured run.

## Reaching a tenant app without a domain

```bash
lb=$(kubectl -n envoy-gateway-system get svc -l gateway.envoyproxy.io/owning-gateway-name=shared -o jsonpath='{.items[0].status.loadBalancer.ingress[0].hostname}')
ip=$(getent hosts "$lb" | awk '{print $1; exit}')
curl --cacert platform-ca.crt --resolve podinfo-team-a.dev.eks-gitops.internal:443:$ip https://podinfo-team-a.dev.eks-gitops.internal
```

`platform-ca.crt` is `ca.crt` from secret `cert-manager/platform-ca`.

## After a session

```bash
scripts/check-tags.sh        # before destroy: every resource tagged (NFR-25)
scripts/cost-report.sh 7     # the next day: spend by environment (NFR-23)
```
