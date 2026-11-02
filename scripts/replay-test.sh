#!/usr/bin/env bash
# Proves consumers are idempotent: snapshot every service's state, rewind all consumer groups to
# the start of their topics so every event is processed again, then require an identical snapshot.
# Run against a stack that has already processed traffic (e.g. after scripts/load-test.sh).
set -euo pipefail
cd "$(dirname "$0")/.."

CONSUMER_SERVICES=(payment-service reconciliation-service exception-service)
SETTLE_SECONDS="${SETTLE_SECONDS:-45}" # > settlement window (30s) + sweep interval, so the sweep has run
POLL_SECONDS=3
TIMEOUT_SECONDS=300
KAFKA_BIN=/opt/kafka/bin

kafka() { docker compose exec -T kafka "$KAFKA_BIN/$1" --bootstrap-server localhost:9092 "${@:2}"; }
sql() { docker compose exec -T postgres psql -U matchbox -d "$1" -Atc "$2"; }

total_lag() {
  local lag=0 group group_lag
  for group in "${CONSUMER_SERVICES[@]}"; do
    group_lag="$(kafka kafka-consumer-groups.sh --describe --group "$group" 2>/dev/null \
      | awk 'NR > 1 && $6 ~ /^[0-9]+$/ { sum += $6 } END { print sum + 0 }')"
    lag=$((lag + group_lag))
  done
  echo "$lag"
}

pending_outbox() {
  echo $(( $(sql payments "SELECT count(*) FROM outbox") + $(sql reconciliation "SELECT count(*) FROM outbox") \
    + $(sql exceptions "SELECT count(*) FROM outbox") ))
}

await_quiet() {
  local waited=0
  until [[ "$(total_lag)" == 0 && "$(pending_outbox)" == 0 ]]; do
    if (( waited >= TIMEOUT_SECONDS )); then
      echo "Timed out waiting for consumers to catch up" >&2
      exit 1
    fi
    sleep "$POLL_SECONDS"
    waited=$((waited + POLL_SECONDS))
  done
}

end_offsets() {
  local topic
  for topic in payment-requested payment-processed reconciliation-exception exception-updated; do
    echo "topic $topic end-offset $(kafka kafka-get-offsets.sh --topic "$topic" | awk -F: '{ sum += $3 } END { print sum + 0 }')"
  done
}

snapshot() {
  sql payments "SELECT 'payments', status, coalesce(exception_status, '-'), count(*) FROM payments GROUP BY 2, 3 ORDER BY 2, 3"
  sql reconciliation "SELECT 'reconciliation', state, coalesce(exception_type, '-'), count(*) FROM reconciliation_records GROUP BY 2, 3 ORDER BY 2, 3"
  sql exceptions "SELECT 'exceptions', type, status, count(*) FROM exceptions GROUP BY 2, 3 ORDER BY 2, 3"
  curl -s localhost:8081/stats | tr -d '{}"' | tr ',' '\n' | grep -E '^(approved|declined|settlementsDelivered):' | sed 's/^/bank /'
  end_offsets
}

echo "Waiting ${SETTLE_SECONDS}s for settlements and the reconciliation sweep, then for consumers to drain..."
sleep "$SETTLE_SECONDS"
await_quiet
before="$(snapshot)"
duplicate_charges_before="$(curl -s localhost:8081/stats | grep -o '"duplicateChargeAttempts":[0-9]*' | cut -d: -f2)"
echo "$before"

echo
echo "Stopping consumers and rewinding every consumer group to the earliest offset..."
docker compose stop "${CONSUMER_SERVICES[@]}" >/dev/null
for group in "${CONSUMER_SERVICES[@]}"; do
  kafka kafka-consumer-groups.sh --group "$group" --reset-offsets --to-earliest --all-topics --execute >/dev/null
done
echo "Replaying $(total_lag) events..."
docker compose start "${CONSUMER_SERVICES[@]}" >/dev/null
sleep "$POLL_SECONDS"
await_quiet
after="$(snapshot)"
duplicate_charges_after="$(curl -s localhost:8081/stats | grep -o '"duplicateChargeAttempts":[0-9]*' | cut -d: -f2)"

echo
if [[ "$before" == "$after" ]]; then
  echo "PASS: state, published events and bank charges are identical after replaying every event."
  echo "      Bank calls during replay: $((duplicate_charges_after - duplicate_charges_before)) (charges are skipped once a payment is terminal)."
else
  echo "FAIL: replay changed state:"
  diff <(echo "$before") <(echo "$after") || true
  exit 1
fi
