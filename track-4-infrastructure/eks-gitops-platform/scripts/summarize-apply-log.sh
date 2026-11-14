#!/usr/bin/env bash
# Turns a terraform apply log into time per stage (NFR-1): network, control
# plane, nodes, identity, access host. Terraform prints
# "<address>: Creation complete after 9m41s" for every resource.
#
#   terraform apply ... | tee apply.log;  scripts/summarize-apply-log.sh apply.log
set -euo pipefail

log="${1:?usage: summarize-apply-log.sh <apply.log>}"

to_seconds() {  # 9m41s -> 581
  local t="$1" s=0
  [[ "$t" =~ ([0-9]+)h ]] && s=$((s + BASH_REMATCH[1] * 3600))
  [[ "$t" =~ ([0-9]+)m ]] && s=$((s + BASH_REMATCH[1] * 60))
  [[ "$t" =~ ([0-9]+)s ]] && s=$((s + BASH_REMATCH[1]))
  echo "$s"
}

printf '%-28s %s\n' "Stage" "Slowest resource (s)"
for pair in network:network control-plane:cluster.module.eks.aws_eks_cluster nodes:cluster.module.eks.module.eks_managed_node_group identity:workload_identity access-host:access_host; do
  label="${pair%%:*}"; stage="${pair#*:}"
  max=0; name=""
  while read -r addr dur; do
    s=$(to_seconds "$dur")
    (( s > max )) && { max=$s; name=$addr; }
  done < <(sed -n "s/^\(module\.${stage}[^:]*\): Creation complete after \([0-9hms]*\).*/\1 \2/p" "$log")
  printf '%-28s %5s  %s\n' "$label" "$max" "$name"
done
