#!/usr/bin/env bash
set -euo pipefail
base=${MOCK_URL:-http://localhost:8088}
case ${1:-get} in
 get) curl --fail-with-body -sS "$base/queue-settings" ;;
 patch) curl --fail-with-body -sS -X PATCH "$base/queue-settings" -H 'Content-Type: application/json' --data "${2:?JSON patch required}" ;;
 fail) curl --fail-with-body -sS -X POST "$base/control" -H 'Content-Type: application/json' --data '{"status":503}' ;;
 recover) curl --fail-with-body -sS -X POST "$base/control" -H 'Content-Type: application/json' --data '{"status":200}' ;;
 reset) curl --fail-with-body -sS -X POST "$base/reset" ;;
 *) printf 'Usage: settings.sh get|patch JSON|fail|recover|reset\n' >&2; exit 1 ;;
esac
printf '\n'
