#!/usr/bin/env bash
# Notification checkpoint (RFC 0011, plan N03): the notification-service alone, live, without order or
# inventory. Publishes the contract examples with fresh ids and checks S3 and the notifications table:
#   1. TicketsIssued ×5 (same messageId) on ticketpro-inventory → 1 receipt receipts/{orderId}.json in S3
#      with the order and its tickets, 1 notifications row, 1 processed_messages row, 4 duplicates logged.
#   2. OrderCancelled ×5 (same messageId) on ticketpro-orders → 1 notifications row and no receipt.
# Usage: scripts/notification-check.sh   (stack up: docker compose up -d && scripts/wait-healthy.sh)
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh; load_env; . scripts/lib/scenario.sh
SCENARIO=notification-check
BUCKET="${RECEIPTS_BUCKET:-ticketpro-receipts}"

# example NAME ORDER MESSAGE → contract example with fresh ids and timestamp
example() {
  jq -c --arg o "$2" --arg m "$3" --arg c "$(id)" --arg u "$(id)" --arg t "$(now_iso)" \
    '.messageId = $m | .correlationId = $c | .occurredAt = $t | .payload.orderId = $o | .payload.userId = $u' \
    "contracts/examples/$1.json"
}
# rows KIND ORDER [SECONDS=20] → notifications rows of that kind for the order, once there is one
rows() {
  local n=0
  for _ in $(seq 1 $(( ${3:-20} * 2 ))); do
    n=$(sql notifications "SELECT count(*) FROM notifications WHERE order_id = '$2' AND kind = '$1'")
    [ "${n:-0}" -ge 1 ] && break; sleep 0.5
  done
  echo "${n:-0}"
}
duplicates() { # MESSAGE SINCE → duplicate=true log lines for the message
  docker compose logs notification-service --since "$2" 2>/dev/null | grep "$1" | grep -c '"duplicate":true'
}

# 1. TicketsIssued: receipt in S3 + notification, once.
ORDER=$(id); MESSAGE=$(id); since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
ENV=$(example TicketsIssued.v1 "$ORDER" "$MESSAGE")
for _ in 1 2 3 4 5; do publish ticketpro-inventory TicketsIssued 1 "$ENV"; done
info "published TicketsIssued messageId $MESSAGE 5 times (order $ORDER)"
n=$(rows TICKETS_ISSUED "$ORDER")
for _ in $(seq 1 20); do [ "$(duplicates "$MESSAGE" "$since")" -ge 4 ] && break; sleep 0.5; done
[ "$n" = 1 ] && pass "1 TICKETS_ISSUED notification for 5 deliveries" || fail "$n TICKETS_ISSUED notifications"
p=$(sql notifications "SELECT count(*) FROM processed_messages WHERE message_id = '$MESSAGE'")
[ "$p" = 1 ] && pass "1 processed_messages row" || fail "$p processed_messages rows"
d=$(duplicates "$MESSAGE" "$since")
[ "$d" -ge 4 ] && pass "$d deliveries logged duplicate=true" || fail "$d duplicate logs (want 4)"
receipt=$(awsl s3 cp "s3://$BUCKET/receipts/$ORDER.json" - 2>/dev/null)
[ "$(echo "$receipt" | jq -r '.orderId // empty' 2>/dev/null)" = "$ORDER" ] \
  && pass "receipt s3://$BUCKET/receipts/$ORDER.json has the order" || fail "no receipt for $ORDER in s3://$BUCKET"
tickets=$(echo "$receipt" | jq '.tickets | length' 2>/dev/null)
[ "${tickets:-0}" = 2 ] && pass "receipt lists the 2 tickets" || fail "receipt lists '${tickets:-0}' tickets (want 2)"

# 2. OrderCancelled: notification only, once.
ORDER=$(id); MESSAGE=$(id)
ENV=$(example OrderCancelled.v1 "$ORDER" "$MESSAGE")
for _ in 1 2 3 4 5; do publish ticketpro-orders OrderCancelled 1 "$ENV"; done
info "published OrderCancelled messageId $MESSAGE 5 times (order $ORDER)"
rows ORDER_CANCELLED "$ORDER" >/dev/null # wait for the first one
sleep 2 # let the other deliveries arrive
n=$(sql notifications "SELECT count(*) FROM notifications WHERE order_id = '$ORDER' AND kind = 'ORDER_CANCELLED'")
[ "$n" = 1 ] && pass "1 ORDER_CANCELLED notification for 5 deliveries" || fail "$n ORDER_CANCELLED notifications"
awsl s3api head-object --bucket "$BUCKET" --key "receipts/$ORDER.json" >/dev/null 2>&1 \
  && fail "a cancelled order got a receipt" || pass "no receipt for the cancelled order"

finish
