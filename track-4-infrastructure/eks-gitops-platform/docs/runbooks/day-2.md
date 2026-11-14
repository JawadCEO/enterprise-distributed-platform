# Runbook: day-2 operations (Phase 7)

Every change here is a pull request. Nothing is changed with `kubectl` or the AWS console.

## Upgrade an add-on (NFR-19)

1. `scripts/check-chart-versions.sh dev` lists pinned and newest versions.
2. On a branch, change the add-on's version in `gitops/root/values-dev.yaml`. Read the chart's release notes for breaking values first.
3. Merge. ArgoCD upgrades it on dev within about 3 minutes; watch it reach Healthy.
4. Promote: `scripts/promote.sh <add-on>`, commit `values-prod.yaml` on a new branch, open a pull request, merge (FR-9).

Rollback: revert the merge commit. ArgoCD returns to the previous version.

## Upgrade the Kubernetes version (NFR-19)

EKS upgrades one minor version at a time: control plane, then nodes, then add-ons.

1. Check the target version is in standard support and every add-on chart supports it.
2. On a branch, raise `kubernetes_version` in `terraform/envs/dev/variables.tf` by one minor version.
3. The pull request's plan shows the control plane and node group updates. Merge and approve the `aws-dev` job.
4. Terraform upgrades the control plane, then rolls the managed node group. Karpenter replaces its own nodes, because the EC2NodeClass uses the latest AL2023 image for the new version.
5. Run `tests/tenancy/run.sh` and `scripts/wait-for-sync.sh`.

Rollback: EKS cannot downgrade a control plane. The rollback is a rebuild at the previous version (aws-session runbook), which is why the version is tested on dev first.

## Roll back a bad sync

| Situation | Action |
|---|---|
| A merged change breaks an add-on or tenant | `git revert <commit>` on a branch, pull request, merge. ArgoCD syncs the previous state |
| Urgent, before a review is possible | In the ArgoCD UI, disable auto-sync on that Application and use History and Rollback, then revert in Git and re-enable auto-sync. The UI step alone is reverted by self-heal once auto-sync is back |
| Policy blocks a needed deploy | Change the constraint to `enforcementAction: warn` by pull request, never by editing it live |

Drill: merge a change that sets a tenant's podinfo image to a tag that does not exist, watch `ArgoAppDegraded` fire, then revert it and time the recovery.

## Node scaling (FR-12)

```bash
kubectl -n team-a scale deployment podinfo --replicas=30   # pending pods trigger Karpenter
kubectl get nodes -l karpenter.sh/nodepool=tenants -w
```

Then revert the replica count in Git, or let ArgoCD revert it. Karpenter removes the empty nodes after one minute (consolidation). On the laptop, the quota caps this at 20 pods instead.
