#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .secrets
chmod 700 .secrets
for secret in mqAppPassword mqAdminPassword; do
  if [[ ! -f ".secrets/$secret" ]]; then printf '%s' "$(openssl rand -hex 24)" > ".secrets/$secret"; fi
  chmod 644 ".secrets/$secret"
done
printf 'Lab secrets ready; existing passwords preserved.\n'
