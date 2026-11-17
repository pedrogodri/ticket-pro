#!/usr/bin/env bash
# Autoscaling demo (ADR-0015, bonus 5): a backlog on ticketpro-payment-service makes the autoscaler add
# payment replicas up to AUTOSCALER_MAX_REPLICAS; once the queue drains and the cooldown passes, it
# removes the extra ones down to AUTOSCALER_MIN_REPLICAS. The peak depends on how fast the batches go out.
# The compose replicas are paused while the backlog builds (the consumer only records PENDING and acks,
# so it would otherwise drain the queue before the autoscaler looks at it); the new replicas drain it.
# Usage: scripts/demo-autoscale.sh [messages=40]
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh
load_env

MESSAGES="${1:-40}"
MIN="${AUTOSCALER_MIN_REPLICAS:-2}"
MAX="${AUTOSCALER_MAX_REPLICAS:-5}"
REGION="${AWS_REGION:-us-east-1}"
TOPIC="arn:aws:sns:$REGION:000000000000:ticketpro-orders"
QUEUE="http://ministack:4566/000000000000/ticketpro-payment-service"
failures=0
pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
id() { uuidgen | tr '[:upper:]' '[:lower:]'; }
replicas() { docker ps -q --filter label=com.docker.compose.project=ticketpro --filter label=com.docker.compose.service=payment-service | wc -l | tr -d ' '; }
depth() { awsl sqs get-queue-attributes --queue-url "$QUEUE" --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
  | jq '[.Attributes[] | tonumber] | add'; }
# wait_for SECONDS CMD... → true once CMD succeeds
wait_for() { local s=$1; shift; for _ in $(seq 1 "$s"); do "$@" && return 0; sleep 1; done; return 1; }

ORIGINAL=$(docker compose ps -q payment-service)
docker pause $ORIGINAL >/dev/null
trap 'docker unpause $ORIGINAL >/dev/null 2>&1' EXIT
echo "info  paused the compose payment replicas; replicas=$(replicas)"

sent=0
while [ "$sent" -lt "$MESSAGES" ]; do
  entries=$(for i in $(seq 1 10); do
    payload=$(jq -cn --arg o "$(id)" --arg u "$(id)" '{orderId:$o, userId:$u, amountCents:15000, currency:"BRL"}')
    jq -cn --arg i "m$i" --arg m "$(id)" --arg c "$(id)" --arg t "$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" --argjson p "$payload" \
      '{Id:$i, Message:({messageId:$m, correlationId:$c, type:"PaymentRequested", version:1, occurredAt:$t, payload:$p}|tostring),
        MessageAttributes:{type:{DataType:"String",StringValue:"PaymentRequested"}, version:{DataType:"String",StringValue:"1"}}}'
  done | jq -cs .)
  awsl sns publish-batch --topic-arn "$TOPIC" --publish-batch-request-entries "$entries" >/dev/null || { fail "publish failed"; exit 1; }
  sent=$((sent + 10))
done
pass "published $sent PaymentRequested; queue depth $(depth)"

# The autoscaler may react while the batches are still being published, so it can peak below MAX:
# what matters is that it scales above MIN with the backlog and never beyond MAX.
peak=0
track() { local n; n=$(replicas); [ "$n" -gt "$peak" ] && peak=$n; return 0; }
above_min() { track; [ "$(replicas)" -gt "$MIN" ]; }
wait_for 60 above_min && pass "autoscaler scaled payment-service above $MIN replicas" || fail "replicas stayed at $(replicas)"

drained() { track; [ "$(depth)" -eq 0 ]; }
wait_for 90 drained && pass "the new replicas drained the queue (peak: $peak replicas)" || fail "queue depth still $(depth)"
[ "$peak" -le "$MAX" ] && pass "never above AUTOSCALER_MAX_REPLICAS=$MAX" || fail "peak $peak above $MAX"

docker unpause $ORIGINAL >/dev/null; trap - EXIT
at_min() { [ "$(replicas)" -le "$MIN" ]; }
wait_for 120 at_min && pass "after the cooldown it scaled back to $(replicas) replicas" || fail "replicas stayed at $(replicas) (want $MIN)"

docker compose logs autoscaler --since 5m 2>/dev/null | grep -o '"depth":[0-9]*,"current":[0-9]*,"desired":[0-9]*' | uniq | sed 's/^/info  /' | tail -8
echo "demo-autoscale: $failures failed"
[ "$failures" -eq 0 ]
