#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/init-lab.sh
kubectl apply -f k8s/namespace.yaml
if ! kubectl -n ibm-mq-spring-lab get secret mq-lab-passwords >/dev/null 2>&1; then
  kubectl -n ibm-mq-spring-lab create secret generic mq-lab-passwords --from-file=mqAppPassword=.secrets/mqAppPassword --from-file=mqAdminPassword=.secrets/mqAdminPassword
fi
kubectl apply -k k8s
kubectl -n ibm-mq-spring-lab rollout status statefulset/mq --timeout=600s
kubectl -n ibm-mq-spring-lab rollout status deployment/mock --timeout=180s
kubectl -n ibm-mq-spring-lab rollout status deployment/agent --timeout=180s
