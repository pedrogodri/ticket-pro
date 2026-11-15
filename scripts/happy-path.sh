#!/usr/bin/env bash
# F2 checkpoint: the purchase happy path through the gateway only (docs/roadmap.md §F2).
#   register → login → catalog → POST /orders (202) → poll until final → my tickets → receipt in S3
#   → the same Idempotency-Key returns the same order.
# PAYMENT_FAILURE_RATE defaults to 0.1, so a CANCELLED(PAYMENT_FAILED) order is retried (up to 5 orders).
# Exit 0 only if every step passes. Usage: scripts/happy-path.sh [base_url=http://localhost:8080]
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh
load_env

BASE="${1:-http://localhost:8080}"
failures=0
pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
die() { fail "$1"; echo "happy-path: aborted"; exit 1; }

uuid() { uuidgen | tr '[:upper:]' '[:lower:]'; }

# request METHOD PATH [BODY] [extra curl args...] → sets STATUS and BODY
request() {
  local method="$1" path="$2" body="${3:-}"
  shift 3 2>/dev/null || shift $#
  local out
  out="$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H 'content-type: application/json' \
    ${TOKEN:+-H "Authorization: Bearer $TOKEN"} ${body:+-d "$body"} "$@")"
  STATUS="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
}

# 1. register
EMAIL="happy-$(uuid)@exemplo.com"
TOKEN=""
request POST /auth/register "{\"email\":\"$EMAIL\",\"password\":\"correct horse battery\",\"name\":\"Happy Path\"}"
[ "$STATUS" = 201 ] && pass "register → 201" || die "register → $STATUS $BODY"

# 2. login
request POST /auth/login "{\"email\":\"$EMAIL\",\"password\":\"correct horse battery\"}"
TOKEN="$(echo "$BODY" | jq -r '.accessToken // empty')"
[ "$STATUS" = 200 ] && [ -n "$TOKEN" ] && pass "login → token" || die "login → $STATUS $BODY"

# 3. catalog
request GET /events ""
EVENT_ID="$(echo "$BODY" | jq -r '.items[0].id // empty')"
[ "$STATUS" = 200 ] && [ -n "$EVENT_ID" ] && pass "GET /events → $(echo "$BODY" | jq '.items | length') event(s)" || die "GET /events → $STATUS $BODY"
request GET "/events/$EVENT_ID" ""
SECTOR_ID="$(echo "$BODY" | jq -r '[.sectors[] | select(.available >= 2)][0].id // empty')"
[ -n "$SECTOR_ID" ] && pass "GET /events/{id} → sector with stock" || die "no sector with available >= 2: $BODY"

# 4–6. order until one is CONFIRMED (payment may fail by design)
ORDER_ID=""
KEY=""
for attempt in 1 2 3 4 5; do
  KEY="$(uuid)"
  request POST /orders "{\"eventId\":\"$EVENT_ID\",\"sectorId\":\"$SECTOR_ID\",\"quantity\":2}" -H "Idempotency-Key: $KEY" -D /dev/stderr 2>/tmp/happy-headers.$$
  LOCATION="$(grep -i '^location:' /tmp/happy-headers.$$ | tr -d '\r' | awk '{print $2}')"
  rm -f /tmp/happy-headers.$$
  ORDER_ID="$(echo "$BODY" | jq -r '.orderId // empty')"
  if [ "$STATUS" != 202 ] || [ "$LOCATION" != "/orders/$ORDER_ID" ]; then
    die "POST /orders → $STATUS (Location: ${LOCATION:-none}) $BODY"
  fi
  pass "POST /orders → 202 + Location (attempt $attempt)"

  final=""
  for _ in $(seq 1 60); do
    request GET "/orders/$ORDER_ID" ""
    final="$(echo "$BODY" | jq -r 'select(.status == "CONFIRMED" or .status == "CANCELLED") | "\(.status) \(.cancelReason // "")"')"
    [ -n "$final" ] && break
    sleep 0.5
  done
  case "$final" in
    CONFIRMED*) pass "order $ORDER_ID → CONFIRMED"; break ;;
    "CANCELLED PAYMENT_FAILED") echo "info  order $ORDER_ID → CANCELLED(PAYMENT_FAILED), retrying"; ORDER_ID="" ;;
    *) die "order $ORDER_ID did not reach a final state in 30s (last: $BODY)" ;;
  esac
done
[ -n "$ORDER_ID" ] || die "no CONFIRMED order in 5 attempts"

# 7. my tickets (read model, eventually consistent)
count=0
for _ in $(seq 1 30); do
  request GET "/me/tickets?limit=50" ""
  count="$(echo "$BODY" | jq --arg o "$ORDER_ID" '[.items[] | select(.orderId == $o)] | length')"
  [ "$count" = 2 ] && break
  sleep 0.5
done
[ "$count" = 2 ] && pass "GET /me/tickets → 2 tickets for the order" || fail "GET /me/tickets → $count tickets for $ORDER_ID"

# 8. receipt in S3
found=""
for _ in $(seq 1 30); do
  if awsl s3api head-object --bucket "$RECEIPTS_BUCKET" --key "receipts/$ORDER_ID.json" >/dev/null 2>&1; then found=yes; break; fi
  sleep 0.5
done
[ -n "$found" ] && pass "receipt s3://$RECEIPTS_BUCKET/receipts/$ORDER_ID.json exists" || fail "receipt for $ORDER_ID not in S3"

# 9. idempotency
request POST /orders "{\"eventId\":\"$EVENT_ID\",\"sectorId\":\"$SECTOR_ID\",\"quantity\":2}" -H "Idempotency-Key: $KEY"
same="$(echo "$BODY" | jq -r '.orderId // empty')"
[ "$STATUS" = 200 ] && [ "$same" = "$ORDER_ID" ] && pass "same Idempotency-Key → 200 with the same order" || fail "idempotent retry → $STATUS $BODY"

echo "happy-path: $failures failed"
[ "$failures" -eq 0 ]
