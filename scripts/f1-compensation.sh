#!/usr/bin/env bash
# F1 compensation (RFC 0003 §5.6): PAYMENT_FAILURE_RATE=1 → PaymentFailed → OrderCancelled(PAYMENT_FAILED)
# → the inventory returns the stock. Shows `available` before and after.
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh; load_env; . scripts/lib/scenario.sh
SCENARIO=f1

trap 'set_payment >/dev/null 2>&1' EXIT
set_payment PAYMENT_FAILURE_RATE=1 || die "payment did not come back with PAYMENT_FAILURE_RATE=1"
info "payment recreated with PAYMENT_FAILURE_RATE=1"

TOKEN=$(new_user) || die "could not create a user"
before=$(available "$EVENT" "$SECTOR" "$TOKEN")
ORDER=$(place_order "$TOKEN" "$EVENT" "$SECTOR" 2) || die "order not accepted"
state=$(final_state "$TOKEN" "$ORDER")
[ "$state" = "CANCELLED PAYMENT_FAILED" ] && pass "order $ORDER → CANCELLED(PAYMENT_FAILED)" || fail "order $ORDER → $state"

after=""
for _ in $(seq 1 20); do after=$(available "$EVENT" "$SECTOR" "$TOKEN"); [ "$after" = "$before" ] && break; sleep 0.5; done
[ "$after" = "$before" ] && pass "stock returned: available $before → reserved → $after" || fail "available before $before, after $after"
[ "$(sql inventory "SELECT status FROM reservations WHERE order_id = '$ORDER'")" = RELEASED ] && pass "reservation RELEASED" || fail "reservation not RELEASED"
finish
