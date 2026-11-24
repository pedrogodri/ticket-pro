#!/usr/bin/env bash
# Payment checkpoint (RFC 0009, R5, R1): the payment-service alone, live, without order or inventory.
# Publishes the events the order would send (PaymentRequested, RefundRequested) to ticketpro-orders
# and checks the payments database and outbox:
#   1. PaymentRequested ×3 (same messageId) + 1 with a new messageId, same order → 1 payment, 1 charge,
#      1 PaymentApproved carrying the saga correlationId (rate 0).
#   2. RefundRequested ×2 (new messageIds) → 1 refund with the captured amount, 1 PaymentRefunded.
#   3. PAYMENT_FAILURE_RATE=1 → FAILED(DECLINED) + PaymentFailed.
#   4. PAYMENT_PROVIDER_TIMEOUT_MS below the latency → FAILED(PROVIDER_TIMEOUT), no charge recorded.
# Recreates the payment replicas with each setting (set_payment) and restores the defaults on exit.
# Usage: scripts/payment-check.sh   (stack up: docker compose up -d && scripts/wait-healthy.sh)
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh; load_env; . scripts/lib/scenario.sh
SCENARIO=payment-check

# request ORDER CORRELATION MESSAGE AMOUNT → PaymentRequested v1 envelope
request() {
  jq -cn --arg o "$1" --arg c "$2" --arg m "$3" --argjson a "$4" --arg u "$(id)" --arg t "$(now_iso)" \
    '{messageId:$m, correlationId:$c, type:"PaymentRequested", version:1, occurredAt:$t,
      payload:{orderId:$o, userId:$u, amountCents:$a, currency:"BRL"}}'
}
# refund ORDER PAYMENT CORRELATION AMOUNT → RefundRequested v1 envelope (new messageId)
refund() {
  jq -cn --arg o "$1" --arg p "$2" --arg c "$3" --argjson a "$4" --arg m "$(id)" --arg t "$(now_iso)" \
    '{messageId:$m, correlationId:$c, type:"RefundRequested", version:1, occurredAt:$t,
      payload:{orderId:$o, paymentId:$p, amountCents:$a, currency:"BRL", reason:"LATE_APPROVAL"}}'
}
# outcome ORDER [SECONDS=20] → "STATUS REASON" once the payment left PENDING
outcome() {
  local s=""
  for _ in $(seq 1 $(( ${2:-20} * 2 ))); do
    s=$(sql payments "SELECT status || ' ' || coalesce(failure_reason, '') FROM payments WHERE order_id = '$1'")
    case "$s" in APPROVED*|FAILED*) break ;; esac
    sleep 0.5
  done
  echo "$s" | sed 's/ *$//'
}
count() { sql payments "$1"; }

trap 'set_payment >/dev/null 2>&1' EXIT

# 1. Approval, idempotency and correlationId.
set_payment PAYMENT_FAILURE_RATE=0 || die "payment did not come back with PAYMENT_FAILURE_RATE=0"
info "payment recreated with PAYMENT_FAILURE_RATE=0"
ORDER=$(id); CORR=$(id); MSG=$(id)
ENV=$(request "$ORDER" "$CORR" "$MSG" 25000)
for _ in 1 2 3; do publish ticketpro-orders PaymentRequested 1 "$ENV"; done
publish ticketpro-orders PaymentRequested 1 "$(request "$ORDER" "$CORR" "$(id)" 25000)"
state=$(outcome "$ORDER")
[ "$state" = APPROVED ] && pass "PaymentRequested → APPROVED" || fail "payment is '$state' (want APPROVED)"
sleep 2 # the other deliveries may still be in flight
[ "$(count "SELECT count(*) FROM payments WHERE order_id = '$ORDER'")" = 1 ] && pass "1 payment for 4 deliveries (R1)" \
  || fail "$(count "SELECT count(*) FROM payments WHERE order_id = '$ORDER'") payments for the order"
PAYMENT=$(count "SELECT id FROM payments WHERE order_id = '$ORDER'")
[ "$(count "SELECT count(*) FROM provider_charges WHERE payment_id = '$PAYMENT'")" = 1 ] && pass "1 provider charge" \
  || fail "provider charged $(count "SELECT count(*) FROM provider_charges WHERE payment_id = '$PAYMENT'") times"
approved=$(count "SELECT count(*) FROM outbox WHERE type = 'PaymentApproved' AND payload->>'orderId' = '$ORDER' AND correlation_id = '$CORR'")
[ "$approved" = 1 ] && pass "1 PaymentApproved with the saga correlationId" || fail "$approved PaymentApproved with correlationId $CORR"

# 2. Single refund with the captured amount (the message asks for a different one).
for _ in 1 2; do publish ticketpro-orders RefundRequested 1 "$(refund "$ORDER" "$PAYMENT" "$CORR" 1)"; done
refunded=0
for _ in $(seq 1 20); do
  refunded=$(count "SELECT count(*) FROM outbox WHERE type = 'PaymentRefunded' AND payload->>'orderId' = '$ORDER'")
  [ "$refunded" -ge 1 ] && break; sleep 0.5
done
sleep 2
refunds=$(count "SELECT count(*) || ' ' || coalesce(max(amount_cents), 0) FROM refunds WHERE payment_id = '$PAYMENT'")
[ "$refunds" = "1 25000" ] && pass "1 refund of the captured 25000 (2 RefundRequested)" || fail "refunds: '$refunds' (want '1 25000')"
refunded=$(count "SELECT count(*) FROM outbox WHERE type = 'PaymentRefunded' AND payload->>'orderId' = '$ORDER'")
[ "$refunded" = 1 ] && pass "1 PaymentRefunded" || fail "$refunded PaymentRefunded"

# 3. R5: the provider always declines.
set_payment PAYMENT_FAILURE_RATE=1 || die "payment did not come back with PAYMENT_FAILURE_RATE=1"
info "payment recreated with PAYMENT_FAILURE_RATE=1"
ORDER=$(id)
publish ticketpro-orders PaymentRequested 1 "$(request "$ORDER" "$(id)" "$(id)" 10000)"
state=$(outcome "$ORDER")
[ "$state" = "FAILED DECLINED" ] && pass "rate 1 → FAILED(DECLINED)" || fail "payment is '$state' (want FAILED DECLINED)"
failed=$(count "SELECT count(*) FROM outbox WHERE type = 'PaymentFailed' AND payload->>'orderId' = '$ORDER' AND payload->>'reason' = 'DECLINED'")
[ "$failed" = 1 ] && pass "1 PaymentFailed(DECLINED)" || fail "$failed PaymentFailed(DECLINED)"

# 4. R5: the hard timeout cuts a slow provider; nothing was charged.
set_payment PAYMENT_FAILURE_RATE=0 PAYMENT_LATENCY_MS=4000 PAYMENT_PROVIDER_TIMEOUT_MS=1000 \
  || die "payment did not come back with PAYMENT_PROVIDER_TIMEOUT_MS=1000"
info "payment recreated with latency 4000ms and a 1000ms hard timeout"
ORDER=$(id)
publish ticketpro-orders PaymentRequested 1 "$(request "$ORDER" "$(id)" "$(id)" 10000)"
state=$(outcome "$ORDER")
[ "$state" = "FAILED PROVIDER_TIMEOUT" ] && pass "timeout → FAILED(PROVIDER_TIMEOUT)" || fail "payment is '$state' (want FAILED PROVIDER_TIMEOUT)"
charges=$(count "SELECT count(*) FROM provider_charges c JOIN payments p ON p.id = c.payment_id WHERE p.order_id = '$ORDER'")
[ "$charges" = 0 ] && pass "no charge recorded for the timed-out payment" || fail "$charges charges recorded after the timeout"

finish
