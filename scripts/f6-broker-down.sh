#!/usr/bin/env bash
# F6 broker down (R6, RFC 0003 §5.3): `docker pause ministack` for 30 s with an order created meanwhile.
# The order stays PENDING and the outbox grows; after `unpause` the relay drains and the saga completes
# with no intervention.
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh; load_env; . scripts/lib/scenario.sh
SCENARIO=f6
PAUSE_S="${1:-30}"

TOKEN=$(new_user) || die "could not create a user"
trap 'docker unpause "$(docker compose ps -q ministack)" >/dev/null 2>&1' EXIT
docker pause "$(docker compose ps -q ministack)" >/dev/null || die "could not pause ministack"
info "ministack paused for ${PAUSE_S}s"

ORDER=$(place_order "$TOKEN" "$EVENT" "$SECTOR" 1) || die "order not accepted while the broker was down"
pass "POST /orders still answers 202 with the broker down"
sleep 5
status=$(sql orders "SELECT status FROM orders WHERE id = '$ORDER'")
queued=$(sql orders "SELECT count(*) FROM outbox WHERE published_at IS NULL AND payload->>'orderId' = '$ORDER'")
[ "$status" = PENDING ] && pass "order stays PENDING" || fail "order is $status while the broker is down"
[ "$queued" -ge 1 ] && pass "outbox holds $queued unpublished message(s)" || fail "outbox is empty"
sleep $(( PAUSE_S > 5 ? PAUSE_S - 5 : 0 ))

docker unpause "$(docker compose ps -q ministack)" >/dev/null; trap - EXIT
info "ministack resumed"
state=$(final_state "$TOKEN" "$ORDER" 90)
case "$state" in
  CONFIRMED* | CANCELLED*) pass "saga reached a final state after the broker came back: $state" ;;
  AWAITING_PAYMENT)
    late=$(sql orders "SELECT (SELECT published_at FROM outbox WHERE type = 'PaymentRequested' AND payload->>'orderId' = '$ORDER') IS NOT NULL")
    fail "order stuck in AWAITING_PAYMENT: the relays' backoff after the outage pushed the payment past the deadline, and the order has no deadline scanner yet (PaymentRequested published: $late)" ;;
  *) fail "order still $state 90s after unpause" ;;
esac
# The final transition enqueues one more event (OrderConfirmed/OrderCancelled): give the relay a moment.
unpublished() { sql orders "SELECT count(*) FROM outbox WHERE published_at IS NULL AND payload->>'orderId' = '$ORDER'"; }
for _ in $(seq 1 30); do [ "$(unpublished)" = 0 ] && break; sleep 0.5; done
[ "$(unpublished)" = 0 ] && pass "outbox drained" || fail "outbox still has unpublished rows"
finish
