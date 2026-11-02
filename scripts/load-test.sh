#!/usr/bin/env bash
# Drives the running compose stack at RATE payments/s for DURATION, then reports API latency
# (from k6) and end-to-end processing latency (accepted -> charged, from payment-service metrics).
# Usage: RATE=200 DURATION=2m scripts/load-test.sh
set -euo pipefail
cd "$(dirname "$0")/.."

RATE="${RATE:-200}"
DURATION="${DURATION:-2m}"
K6_IMAGE="grafana/k6:1.3.0"
RESULTS_DIR="loadtest/results"
mkdir -p "$RESULTS_DIR"

network="$(docker compose ps -q payment-service | xargs docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}}{{end}}')"

status=0
docker run --rm --network "$network" -v "$PWD/loadtest:/scripts" -e RATE="$RATE" -e DURATION="$DURATION" \
  "$K6_IMAGE" run --summary-export "/scripts/results/summary.json" /scripts/payments.js || status=$?

echo
echo "End-to-end processing time (payment accepted -> charge recorded), payment-service:"
curl -s localhost:8080/actuator/prometheus \
  | grep -E '^matchbox_payment_processing_seconds(_count|_max|\{.*quantile="0.95")' \
  | sed 's/^/  /'
exit "$status"
