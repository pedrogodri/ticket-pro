#!/usr/bin/env bash
# Versioning demo (RFC 0001 §5.5): OrderCreated v1 and v2 are both processed by the inventory
# (v1 upcast to channel API), and version 3 is poison → inventory DLQ.
# Publishes straight to the ticketpro-orders topic, the way the order's outbox does.
# Usage: scripts/demo-versioning.sh
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh
load_env

failures=0
pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
id() { uuidgen | tr '[:upper:]' '[:lower:]'; }
REGION="${AWS_REGION:-us-east-1}"
TOPIC="arn:aws:sns:$REGION:000000000000:ticketpro-orders"
DLQ="http://ministack:4566/000000000000/ticketpro-inventory-service-dlq"
EVENT=0193a6f0-3333-7000-8000-000000000003
SECTOR=0193a6f0-4444-7000-8000-000000000004 # Pista: large stock
USER=0193a6f0-1111-7000-8000-000000000001

sql() { docker compose exec -T inventory-db psql -U inventory_owner -d inventory -tAc "$1"; }
# in_dlq ORDER_ID: true when a DLQ message carries that orderId (peek: visibility 0, nothing deleted).
in_dlq() {
  awsl sqs receive-message --queue-url "$DLQ" --max-number-of-messages 10 --visibility-timeout 0 \
    | jq -e --arg o "$1" '[.Messages[]?.Body | select(contains($o))] | length > 0' >/dev/null
}

# publish VERSION ORDER_ID [CHANNEL]
publish() {
  local payload
  payload=$(jq -cn --arg o "$2" --arg u "$USER" --arg e "$EVENT" --arg s "$SECTOR" --arg c "${3:-}" \
    '{orderId:$o, userId:$u, eventId:$e, sectorId:$s, quantity:1} + (if $c == "" then {} else {channel:$c} end)')
  local env
  env=$(jq -cn --arg m "$(id)" --arg c "$(id)" --arg t "$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" --argjson v "$1" --argjson p "$payload" \
    '{messageId:$m, correlationId:$c, type:"OrderCreated", version:$v, occurredAt:$t, payload:$p}')
  awsl sns publish --topic-arn "$TOPIC" --message "$env" \
    --message-attributes "{\"type\":{\"DataType\":\"String\",\"StringValue\":\"OrderCreated\"},\"version\":{\"DataType\":\"String\",\"StringValue\":\"$1\"}}" >/dev/null
}

# wait_channel ORDER_ID → prints the reservation channel once it exists
wait_channel() {
  local c=""
  for _ in $(seq 1 120); do # up to 30 s: the first long poll after boot can be slow
    c=$(sql "SELECT channel FROM reservations WHERE order_id = '$1'")
    [ -n "$c" ] && break
    sleep 0.25
  done
  echo "$c"
}

V1=$(id); V2=$(id); V3=$(id)

publish 1 "$V1"
c=$(wait_channel "$V1")
[ "$c" = API ] && pass "v1 processed, upcast to channel API" || fail "v1: reservation channel '${c:-none}'"

publish 2 "$V2" WEB
c=$(wait_channel "$V2")
[ "$c" = WEB ] && pass "v2 processed with channel WEB" || fail "v2: reservation channel '${c:-none}'"

publish 3 "$V3" MOBILE
moved=""
for _ in $(seq 1 60); do
  in_dlq "$V3" && { moved=yes; break; }
  sleep 0.5
done
[ -n "$moved" ] && pass "v3 rejected as poison → ticketpro-inventory-service-dlq" || fail "v3 did not reach the DLQ in 30s"
[ -z "$(sql "SELECT 1 FROM reservations WHERE order_id = '$V3'")" ] && pass "v3 had no effect (no reservation)" || fail "v3 created a reservation"

# The stock the demo reserved goes back on the expiry job (no order exists for these ids).
echo "demo-versioning: $failures failed"
[ "$failures" -eq 0 ]
