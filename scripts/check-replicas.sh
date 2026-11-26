#!/usr/bin/env bash
# F2 checkpoint (R1): order, inventory and payment run 2 replicas each, and every replica consumed
# at least one message (log line "message_consumed"). Run after a few scripts/happy-path.sh runs.
# Usage: scripts/check-replicas.sh [runs=3]   (runs happy-path that many times first; 0 skips)
set -uo pipefail
cd "$(dirname "$0")/.."
runs="${1:-3}"
. scripts/lib/trace.sh
failures=0

for _ in $(seq 1 "$runs"); do
  scripts/happy-path.sh >/dev/null || { echo "FAIL  happy-path run failed"; exit 1; }
done

banner info "R4: CONSUMIDORES CONCORRENTES" "order, inventory e payment com 2 réplicas cada na MESMA fila SQS; cada réplica tem que ter consumido"
for svc in order-service inventory-service payment-service; do
  trace_replicas "$svc"
  ids="$(docker compose ps -q "$svc")"
  n="$(echo "$ids" | grep -c . || true)"
  [ "$n" -ge 2 ] || { echo "FAIL  $svc has $n replica(s), expected >= 2"; failures=$((failures + 1)); continue; }
  for id in $ids; do
    name="$(docker inspect -f '{{.Name}}' "$id" | tr -d /)"
    c="$(docker logs "$id" 2>&1 | grep -c message_consumed || true)"
    if [ "$c" -ge 1 ]; then echo "ok    $name consumed $c message(s)"; else echo "FAIL  $name consumed nothing"; failures=$((failures + 1)); fi
  done
done

echo "check-replicas: $failures failed"
[ "$failures" -eq 0 ]
