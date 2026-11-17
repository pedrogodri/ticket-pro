#!/usr/bin/env bash
# F3 duplicate ×5 (ADR-0006): the same OrderCreated envelope (same messageId) published 5 times
# → one reservation; the other deliveries are logged as duplicates.
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh; load_env; . scripts/lib/scenario.sh
SCENARIO=f3

ORDER=$(id); MESSAGE=$(id)
ENV=$(envelope OrderCreated 1 "$(jq -cn --arg o "$ORDER" --arg u "$(id)" --arg e "$EVENT" --arg s "$SECTOR" \
  '{orderId:$o, userId:$u, eventId:$e, sectorId:$s, quantity:1}')" "$MESSAGE")
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
for i in 1 2 3 4 5; do publish ticketpro-orders OrderCreated 1 "$ENV"; done
info "published messageId $MESSAGE 5 times"

for _ in $(seq 1 40); do
  dups=$(docker compose logs inventory-service --since "$since" 2>/dev/null | grep "$MESSAGE" | grep -c '"duplicate":true')
  [ "$dups" -ge 4 ] && break; sleep 0.5
done
n=$(sql inventory "SELECT count(*) FROM reservations WHERE order_id = '$ORDER'")
p=$(sql inventory "SELECT count(*) FROM processed_messages WHERE message_id = '$MESSAGE'")
[ "$n" = 1 ] && pass "1 reservation for the order (5 deliveries)" || fail "$n reservations"
[ "$p" = 1 ] && pass "1 processed_messages row" || fail "$p processed_messages rows"
[ "$dups" -ge 4 ] && pass "$dups deliveries logged duplicate=true" || fail "$dups duplicate logs (want 4)"
finish
