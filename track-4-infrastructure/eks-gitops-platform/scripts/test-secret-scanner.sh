#!/usr/bin/env bash
# Proves the secret scanner works (test data in data.md): commits a freshly
# generated, fake AWS key pair to a throwaway repository outside this one and
# expects gitleaks to fail. Nothing is written to this repository.
set -euo pipefail

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
id="AKIA$(LC_ALL=C tr -dc 'A-Z2-7' </dev/urandom | head -c 16)"
secret="$(LC_ALL=C tr -dc 'A-Za-z0-9/+' </dev/urandom | head -c 40)"

git -C "$tmp" init -q
printf 'aws_access_key_id = %s\naws_secret_access_key = %s\n' "$id" "$secret" > "$tmp/credentials"
git -C "$tmp" add credentials
git -C "$tmp" -c user.name=test -c user.email=test@example.com commit -qm "planted fake key"

if gitleaks detect --source "$tmp" --no-banner >/dev/null 2>&1; then
  echo "FAIL  gitleaks missed a planted AWS key"; exit 1
fi
echo "PASS  gitleaks blocked a planted AWS key"
