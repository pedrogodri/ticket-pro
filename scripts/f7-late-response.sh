#!/usr/bin/env bash
# F7 late response (RFC 0003 §5.6–5.7): PAYMENT_LATENCY_MS > ORDER_PAYMENT_TIMEOUT_MS (the provider's hard
# timeout raised above the latency so it does answer, late). Expect CANCELLED(PAYMENT_TIMEOUT) at the
# deadline, then the late PaymentApproved → RefundRequested → PaymentRefunded.
# Depends on the order's deadline scanner and refund flow and on the payment's refund handler.
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh; load_env; . scripts/lib/scenario.sh
SCENARIO=f7
TIMEOUT_MS="${ORDER_PAYMENT_TIMEOUT_MS:-15000}"
LATENCY_MS=$((TIMEOUT_MS + 5000))

trap 'set_payment >/dev/null 2>&1' EXIT
set_payment PAYMENT_LATENCY_MS=$LATENCY_MS PAYMENT_PROVIDER_TIMEOUT_MS=$((LATENCY_MS + 10000)) PAYMENT_FAILURE_RATE=0 \
  || die "payment did not come back"
info "payment latency ${LATENCY_MS}ms > order deadline ${TIMEOUT_MS}ms"

TOKEN=$(new_user) || die "could not create a user"
ORDER=$(place_order "$TOKEN" "$EVENT" "$SECTOR" 1) || die "order not accepted"
state=$(final_state "$TOKEN" "$ORDER" $(( TIMEOUT_MS / 1000 + 10 )))
[ "$state" = "CANCELLED PAYMENT_TIMEOUT" ] && pass "order cancelled at the deadline: CANCELLED(PAYMENT_TIMEOUT)" \
  || fail "order is '$state' after the deadline (needs the order's deadline scanner)"

refund=""
for _ in $(seq 1 30); do
  refund=$(sql orders "SELECT count(*) FROM outbox WHERE type = 'RefundRequested' AND payload->>'orderId' = '$ORDER'" 2>/dev/null)
  [ "${refund:-0}" -ge 1 ] && break; sleep 1
done
[ "${refund:-0}" -ge 1 ] && pass "late PaymentApproved → RefundRequested" || fail "no RefundRequested for the late approval (order refund flow missing)"
refunded=$(sql payments "SELECT count(*) FROM outbox WHERE type = 'PaymentRefunded' AND payload->>'orderId' = '$ORDER'" 2>/dev/null)
[ "${refunded:-0}" -ge 1 ] && pass "PaymentRefunded published" || fail "no PaymentRefunded (payment refund handler missing)"
finish
