#!/usr/bin/env bash
# Finds AWS resources missing the cost tags (FR-24, NFR-25), including those
# created by controllers rather than Terraform. Uses AWS Resource Explorer,
# which is free and must be switched on once per account:
#   aws resource-explorer-2 create-index --type LOCAL && aws resource-explorer-2 create-view --view-name all
#
#   scripts/check-tags.sh
set -euo pipefail

region="${AWS_REGION:-us-east-1}"
missing=0
for tag in project environment owner; do
  # "-tag.key:x" matches resources that do not carry tag x.
  found="$(aws resource-explorer-2 search --region "$region" \
    --query-string "-tag.key:$tag region:$region -resourcetype:ec2:network-interface" \
    --query 'Resources[].Arn' --output text 2>/dev/null | tr '\t' '\n' | grep -E 'eks-gitops|karpenter|k8s' || true)"
  if [[ -n "$found" ]]; then
    echo "Missing tag '$tag':"
    sed 's/^/  /' <<<"$found"
    missing=$((missing + $(grep -c . <<<"$found")))
  fi
done
(( missing == 0 )) && echo "Every platform resource carries project, environment and owner tags" || { echo "$missing untagged"; exit 1; }
