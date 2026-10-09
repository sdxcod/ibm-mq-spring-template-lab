#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
jar=target/ibm-mq-spring-template-lab-0.1.0.jar
if [[ ! -f "$jar" ]]; then ./mvnw -B -ntp verify; fi
command=${1:-help}
if [[ $# -gt 0 ]]; then shift; fi
exec java -jar "$jar" "--lab.command=$command" "$@"
