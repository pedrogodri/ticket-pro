#!/usr/bin/env bash
# Spike: verifies the MiniStack behaviors the architecture relies on (plan F0, Task 3).
# Prints "PASS <id>" or "FAIL <id>: <observed>" per check; exits 0 only if all pass.
# Requires the stack up (docker compose up -d) and infra-init applied. Leaves queues drained.
set -uo pipefail
cd "$(dirname "$0")/../.."
. scripts/lib/aws.sh
load_env

INV_Q="$INVENTORY_SERVICE_QUEUE_URL"
PAY_Q="$PAYMENT_SERVICE_QUEUE_URL"
INV_DLQ="${INV_Q}-dlq"
failures=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1: $2"; failures=$((failures + 1)); }

envelope() { # $1=type $2=messageId
  printf '{"messageId":"%s","correlationId":"%s","type":"%s","version":1,"occurredAt":"2026-11-12T12:00:00.000Z","payload":{}}' \
    "$2" "$(uuidgen | tr 'A-Z' 'a-z')" "$1"
}
publish() { # $1=topic arn $2=type [extra aws args...] -> prints the envelope messageId (marker)
  local topic="$1" type="$2" id
  shift 2
  id="$(uuidgen | tr 'A-Z' 'a-z')"
  awsl sns publish "$@" --topic-arn "$topic" --message "$(envelope "$type" "$id")" \
    --message-attributes "{\"type\":{\"DataType\":\"String\",\"StringValue\":\"$type\"},\"version\":{\"DataType\":\"String\",\"StringValue\":\"1\"}}" >/dev/null || return 1
  echo "$id"
}
has() { grep -q "$1"; }   # receive JSON contains marker $1 (SNS envelope or raw body)
recv() { # $1=queue url [$2=visibility override] -> prints raw receive JSON ({} when empty)
  local vis=()
  [ -n "${2:-}" ] && vis=(--visibility-timeout "$2")
  awsl sqs receive-message --queue-url "$1" --max-number-of-messages 10 --wait-time-seconds 2 \
    --attribute-names All --message-attribute-names All ${vis[@]+"${vis[@]}"} 2>/dev/null || echo '{}'
}
count() { jq -s '(.[0].Messages // []) | length'; }  # empty CLI output (no messages) counts as 0
drain() { # delete everything currently receivable in $1
  local out
  while :; do
    out=$(awsl sqs receive-message --queue-url "$1" --max-number-of-messages 10 --wait-time-seconds 1 2>/dev/null || echo '{}')
    [ "$(echo "$out" | count)" -eq 0 ] && break
    echo "$out" | jq -r '.Messages[].ReceiptHandle' | while read -r h; do
      awsl sqs delete-message --queue-url "$1" --receipt-handle "$h" >/dev/null
    done
  done
}

for q in "$INV_Q" "$PAY_Q" "$INV_DLQ"; do drain "$q"; done

# S1 filter policy routes by `type` attribute
publish "$ORDERS_TOPIC_ARN" OrderCreated >/dev/null
inv=$(recv "$INV_Q"); pay=$(recv "$PAY_Q")
publish "$ORDERS_TOPIC_ARN" PaymentRequested >/dev/null
inv2=$(recv "$INV_Q"); pay2=$(recv "$PAY_Q")
if [ "$(echo "$inv" | count)" -eq 1 ] && [ "$(echo "$pay" | count)" -eq 0 ] \
  && [ "$(echo "$inv2" | count)" -eq 0 ] && [ "$(echo "$pay2" | count)" -eq 1 ]; then
  pass S1-filter
else
  fail S1-filter "inventory got $(echo "$inv" | count)/$(echo "$inv2" | count), payment got $(echo "$pay" | count)/$(echo "$pay2" | count) (want 1/0 and 0/1)"
fi

# S2 body format without raw delivery
body=$(echo "$inv" | jq -r '.Messages[0].Body // empty')
if echo "$body" | jq -e '.Type == "Notification" and (.Message | fromjson | .type == "OrderCreated")' >/dev/null 2>&1; then
  attrs_in_sns=$(echo "$body" | jq -r '.MessageAttributes.type.Value // "absent"')
  attrs_in_sqs=$(echo "$inv" | jq -r '.Messages[0].MessageAttributes.type.StringValue // "absent"')
  pass "S2-body (SNS envelope; type attr in SNS body: $attrs_in_sns; in SQS attrs: $attrs_in_sqs)"
else
  fail S2-body "unexpected body: ${body:0:200}"
fi
drain "$INV_Q"; drain "$PAY_Q"

# S3 raw message delivery on a temporary subscription
raw_q=$(awsl sqs create-queue --queue-name spike-raw | jq -r '.QueueUrl')
raw_arn=$(awsl sqs get-queue-attributes --queue-url "$raw_q" --attribute-names QueueArn | jq -r '.Attributes.QueueArn')
sub=$(awsl sns subscribe --topic-arn "$ORDERS_TOPIC_ARN" --protocol sqs --notification-endpoint "$raw_arn" \
  --attributes '{"RawMessageDelivery":"true"}' | jq -r '.SubscriptionArn')
publish "$ORDERS_TOPIC_ARN" OrderCreated >/dev/null
raw=$(recv "$raw_q")
raw_body=$(echo "$raw" | jq -r '.Messages[0].Body // empty')
raw_attr=$(echo "$raw" | jq -r '.Messages[0].MessageAttributes.type.StringValue // "absent"')
if echo "$raw_body" | jq -e '.type == "OrderCreated" and has("messageId")' >/dev/null 2>&1 && [ "$raw_attr" = "OrderCreated" ]; then
  pass S3-raw
else
  fail S3-raw "body=${raw_body:0:160} sqs type attr=$raw_attr"
fi
awsl sns unsubscribe --subscription-arn "$sub" >/dev/null 2>&1
awsl sqs delete-queue --queue-url "$raw_q" >/dev/null 2>&1
drain "$INV_Q"

# S4 ApproximateReceiveCount increments
publish "$ORDERS_TOPIC_ARN" OrderCreated >/dev/null
c1=$(recv "$INV_Q" 1 | jq -r '.Messages[0].Attributes.ApproximateReceiveCount // "none"')
sleep 2
c2=$(recv "$INV_Q" 1 | jq -r '.Messages[0].Attributes.ApproximateReceiveCount // "none"')
[ "$c1" = "1" ] && [ "$c2" = "2" ] && pass S4-receivecount || fail S4-receivecount "counts $c1 then $c2 (want 1 then 2)"
drain "$INV_Q"

# S5 ChangeMessageVisibility 0 makes the message visible immediately
publish "$ORDERS_TOPIC_ARN" OrderCreated >/dev/null
h=$(recv "$INV_Q" | jq -r '.Messages[0].ReceiptHandle // empty')
if [ -n "$h" ] && awsl sqs change-message-visibility --queue-url "$INV_Q" --receipt-handle "$h" --visibility-timeout 0 >/dev/null 2>&1; then
  again=$(recv "$INV_Q" | count)
  [ "$again" -eq 1 ] && pass S5-visibility0 || fail S5-visibility0 "message not visible after visibility 0"
else
  fail S5-visibility0 "receive or change-message-visibility failed"
fi
drain "$INV_Q"

# S6 redrive to DLQ after maxReceiveCount (3)
drain "$INV_DLQ"
m6=$(publish "$ORDERS_TOPIC_ARN" OrderCreated)
for _ in 1 2 3; do recv "$INV_Q" 1 >/dev/null; sleep 2; done
fourth=$(recv "$INV_Q" 1); sleep 1
dlq=$(recv "$INV_DLQ")
if ! echo "$fourth" | has "$m6" && echo "$dlq" | has "$m6"; then
  pass S6-redrive
else
  fail S6-redrive "marker in queue on 4th receive: $(echo "$fourth" | has "$m6" && echo yes || echo no); in dlq: $(echo "$dlq" | has "$m6" && echo yes || echo no)"
fi
drain "$INV_Q"; drain "$INV_DLQ"

# S7 ApproximateNumberOfMessages reflects depth
for _ in 1 2 3 4 5; do publish "$ORDERS_TOPIC_ARN" OrderCreated >/dev/null; done
depth=0
for _ in 1 2 3 4 5; do
  depth=$(awsl sqs get-queue-attributes --queue-url "$INV_Q" --attribute-names ApproximateNumberOfMessages | jq -r '.Attributes.ApproximateNumberOfMessages // 0')
  [ "$depth" = "5" ] && break; sleep 1
done
[ "$depth" = "5" ] && pass S7-depth || fail S7-depth "ApproximateNumberOfMessages=$depth (want 5)"
drain "$INV_Q"

# S8 docker pause keeps queues and messages; publishing during pause fails on the client side
cid=$(docker compose ps -q ministack)
m8=$(publish "$ORDERS_TOPIC_ARN" OrderCreated)
docker pause "$cid" >/dev/null
start=$(date +%s)
if m8late=$(publish "$ORDERS_TOPIC_ARN" OrderCreated --cli-connect-timeout 3 --cli-read-timeout 3); then
  during="succeeded (unexpected)"
else
  during="failed"
  m8late=""
fi
elapsed=$(( $(date +%s) - start ))
[ "$elapsed" -lt 30 ] && sleep $(( 30 - elapsed ))
docker unpause "$cid" >/dev/null
sleep 3
after=$(recv "$INV_Q")
kept=no; echo "$after" | has "$m8" && kept=yes
late=$(echo "$after" | count)
if [ "$during" = "failed" ] && [ "$kept" = "yes" ]; then
  pass "S8-pause (pre-pause message kept; publish during pause failed client-side; messages after unpause: $late — >1 means the timed-out publish was still delivered late)"
else
  fail S8-pause "publish during pause $during; pre-pause message kept: $kept"
fi
drain "$INV_Q"

[ "$failures" -eq 0 ] && { echo "spike: all checks passed"; exit 0; }
echo "spike: $failures check(s) failed"; exit 1
