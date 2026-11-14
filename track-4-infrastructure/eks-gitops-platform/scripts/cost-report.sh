#!/usr/bin/env bash
# Spend by environment for the last N days, from Cost Explorer (FR-24,
# NFR-22 to NFR-24). The project/environment tags must be activated as cost
# allocation tags once in the Billing console. Each call costs $0.01.
#
#   scripts/cost-report.sh [days]
set -euo pipefail

days="${1:-30}"
start="$(date -u -d "-$days days" +%Y-%m-%d 2>/dev/null || date -u -v-"$days"d +%Y-%m-%d)"
end="$(date -u +%Y-%m-%d)"

echo "Spend $start to $end, by environment tag"
aws ce get-cost-and-usage \
  --time-period "Start=$start,End=$end" \
  --granularity DAILY \
  --metrics UnblendedCost \
  --filter '{"Tags":{"Key":"project","Values":["eks-gitops-platform"]}}' \
  --group-by Type=TAG,Key=environment \
  --query 'ResultsByTime[].[TimePeriod.Start, Groups[].[Keys[0], Metrics.UnblendedCost.Amount]]' \
  --output text

echo
echo "Total, all services, same period"
aws ce get-cost-and-usage \
  --time-period "Start=$start,End=$end" \
  --granularity MONTHLY \
  --metrics UnblendedCost \
  --query 'ResultsByTime[].Total.UnblendedCost.Amount' --output text
