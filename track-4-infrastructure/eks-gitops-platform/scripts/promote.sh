#!/usr/bin/env bash
# Promotion (FR-9): copies an add-on's version from dev to prod in Git.
# Commit the change on a branch and open a pull request; merging it is the
# promotion.
#
#   scripts/promote.sh <add-on>      e.g. scripts/promote.sh cert-manager
set -euo pipefail

addon="${1:?usage: promote.sh <add-on>}"
root="$(cd "$(dirname "$0")/.." && pwd)/gitops/root"

version="$(sed -n "s/^  $addon: *{.*version: \"\([^\"]*\)\".*/\1/p" "$root/values-dev.yaml")"
[[ -n "$version" ]] || { echo "no version for $addon in values-dev.yaml" >&2; exit 1; }
current="$(sed -n "s/^  $addon: *{.*version: \"\([^\"]*\)\".*/\1/p" "$root/values-prod.yaml")"
[[ "$current" != "$version" ]] || { echo "$addon is already $version in prod"; exit 0; }

sed -i "s/^\(  $addon: *{.*version: \"\)[^\"]*\(\".*\)/\1$version\2/" "$root/values-prod.yaml"
echo "$addon: prod $current -> $version. Commit gitops/root/values-prod.yaml and open a pull request."
