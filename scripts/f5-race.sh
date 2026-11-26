#!/usr/bin/env bash
# F5 race for the last tickets (R2): 100 concurrent POST /orders of 1 unit on the seeded F5 sector (10 units),
# from 20 users (5 each, within the per-user order limit). Expect available = 0, 10 orders through and 90
# CANCELLED(OUT_OF_STOCK). Payments run with PAYMENT_FAILURE_RATE=0 so no reserved unit comes back.
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh; load_env; . scripts/lib/scenario.sh
SCENARIO=f5
USERS=20; PER_USER=5

trap 'set_payment >/dev/null 2>&1' EXIT
set_payment PAYMENT_FAILURE_RATE=0 || die "payment did not come back"
# Test setup only: release what earlier runs left in the F5 sector and refill it to its capacity.
sql inventory "UPDATE reservations SET status = 'RELEASED' WHERE sector_id = '$F5_SECTOR' AND status = 'PENDING';
  UPDATE sectors SET available = capacity WHERE id = '$F5_SECTOR'" >/dev/null
info "F5 sector refilled to $(sql inventory "SELECT available FROM sectors WHERE id = '$F5_SECTOR'") units"

TOKENS=()
trace "criando $USERS usuários (POST /auth/register + /auth/login cada)"
_TRACE_QUIET=1
for _ in $(seq 1 $USERS); do t=$(new_user) || die "could not create users"; TOKENS+=("$t"); done
unset _TRACE_QUIET
info "$USERS users ready"

out=$(mktemp)
banner info "$((USERS * PER_USER)) PEDIDOS AO MESMO TEMPO → $(sql inventory "SELECT available FROM sectors WHERE id = '$F5_SECTOR'") INGRESSOS" "$USERS usuários × $PER_USER pedidos, todos no setor F5"
trace "disparando $((USERS * PER_USER)) POST /orders AO MESMO TEMPO ($PER_USER por usuário) no setor F5, que tem 10 unidades"
for t in "${TOKENS[@]}"; do
  for _ in $(seq 1 $PER_USER); do
    ( curl -s -X POST "$BASE/orders" -H 'content-type: application/json' -H "Authorization: Bearer $t" \
        -H "Idempotency-Key: $(id)" -d "{\"eventId\":\"$F5_EVENT\",\"sectorId\":\"$F5_SECTOR\",\"quantity\":1}" \
        | jq -r '.orderId // empty' >> "$out" ) &
  done
done
wait
accepted=$(grep -c . "$out")
[ "$accepted" = $((USERS * PER_USER)) ] && pass "$accepted orders accepted (202)" || fail "$accepted of $((USERS * PER_USER)) orders accepted"

list=$(sed "s/.*/'&'/" "$out" | paste -sd, -); rm -f "$out"
for _ in $(seq 1 90); do
  pending=$(sql orders "SELECT count(*) FROM orders WHERE id IN ($list) AND status NOT IN ('CONFIRMED','CANCELLED')")
  [ "$pending" = 0 ] && break; sleep 1
done
oos=$(sql orders "SELECT count(*) FROM orders WHERE id IN ($list) AND cancel_reason = 'OUT_OF_STOCK'")
through=$(sql orders "SELECT count(*) FROM orders WHERE id IN ($list) AND status = 'CONFIRMED'")
left=$(sql inventory "SELECT available FROM sectors WHERE id = '$F5_SECTOR'")
if [ "$through" = 10 ] && [ "$oos" = 90 ] && [ "$left" = 0 ]; then kind=ok; else kind=fail; fi
banner "$kind" "ZERO VENDA DUPLA: $through CONFIRMED · $oos OUT_OF_STOCK · estoque $left" \
  "decidido no banco: UPDATE sectors ... WHERE available >= q + CHECK (available >= 0)"
[ "$through" = 10 ] && pass "10 orders CONFIRMED" || fail "$through orders CONFIRMED (want 10)"
[ "$oos" = 90 ] && pass "90 orders CANCELLED(OUT_OF_STOCK)" || fail "$oos orders OUT_OF_STOCK (want 90)"
[ "$left" = 0 ] && pass "available = 0, never negative" || fail "available = $left"
finish
