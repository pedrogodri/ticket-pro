#!/usr/bin/env bash
# Asserts that the AWS resources the architecture depends on exist in MiniStack.
# Exit 0 only if every check passes. Usage: scripts/verify-infra.sh
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh
load_env

failures=0
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }

# --- identity ---------------------------------------------------------------
if account=$(awsl sts get-caller-identity 2>/dev/null | jq -er '.Account'); then
  pass "sts identity (account $account)"
else
  fail "sts get-caller-identity (ministack unreachable?)"
fi


# --- expected topology (docs/arquitetura.md §7; bash 3.2 compatible: no assoc arrays) ---
TOPICS="ingressoja-auth ingressoja-users ingressoja-orders ingressoja-inventory ingressoja-payments"
# service:comma-separated event types
SUBSCRIPTIONS="
gateway:TokenRevoked
auth-service:UserCreationFailed
user-service:UserRegistered,OrderConfirmed,TicketsIssued
order-service:InventoryReserved,InventoryRejected,InventoryReservationExpired,PaymentApproved,PaymentFailed,PaymentRefunded
inventory-service:OrderCreated,OrderConfirmed,OrderCancelled
payment-service:PaymentRequested,RefundRequested
notification-service:TicketsIssued,OrderCancelled
"
topic_of() {
  case "$1" in
    UserRegistered|TokenRevoked) echo ingressoja-auth ;;
    UserCreationFailed) echo ingressoja-users ;;
    OrderCreated|PaymentRequested|OrderConfirmed|OrderCancelled|RefundRequested) echo ingressoja-orders ;;
    InventoryReserved|InventoryRejected|InventoryReservationExpired|TicketsIssued) echo ingressoja-inventory ;;
    PaymentApproved|PaymentFailed|PaymentRefunded) echo ingressoja-payments ;;
    *) echo "unknown-type-$1" ;;
  esac
}
env_name() { echo "$1" | tr 'a-z-' 'A-Z_'; }   # order-service -> ORDER_SERVICE

# --- topics -----------------------------------------------------------------
topics_json=$(awsl sns list-topics 2>/dev/null || echo '{"Topics":[]}')
for t in $TOPICS; do
  arn=$(echo "$topics_json" | jq -r --arg t "$t" '.Topics[].TopicArn | select(endswith(":" + $t))')
  if [ -n "$arn" ]; then pass "topic $t"; else fail "topic $t not found"; continue; fi
  var="$(env_name "${t#ingressoja-}")_TOPIC_ARN"
  expected=$(eval "echo \"\${$var:-}\"")
  if [ "$expected" = "$arn" ]; then pass "env $var"; else fail "env $var='$expected' but topic is '$arn'"; fi
done

# --- queues, DLQs, subscriptions ---------------------------------------------
for entry in $SUBSCRIPTIONS; do
  svc="${entry%%:*}"; types="${entry#*:}"
  q="ingressoja-$svc"; dlq="$q-dlq"
  url=$(awsl sqs get-queue-url --queue-name "$q" 2>/dev/null | jq -r '.QueueUrl // empty')
  dlq_url=$(awsl sqs get-queue-url --queue-name "$dlq" 2>/dev/null | jq -r '.QueueUrl // empty')
  if [ -z "$url" ]; then fail "queue $q not found"; continue; fi
  pass "queue $q"
  if [ -z "$dlq_url" ]; then fail "dlq $dlq not found"; continue; fi
  pass "dlq $dlq"

  var="$(env_name "$svc")_QUEUE_URL"
  expected=$(eval "echo \"\${$var:-}\"")
  if [ "$expected" = "$url" ]; then pass "env $var"; else fail "env $var='$expected' but queue is '$url'"; fi

  attrs=$(awsl sqs get-queue-attributes --queue-url "$url" --attribute-names All | jq '.Attributes')
  dlq_arn=$(awsl sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn | jq -r '.Attributes.QueueArn')
  redrive=$(echo "$attrs" | jq -r '.RedrivePolicy // "{}"')
  if [ "$(echo "$redrive" | jq -r '.deadLetterTargetArn')" = "$dlq_arn" ] && [ "$(echo "$redrive" | jq -r '.maxReceiveCount | tostring')" = "3" ]; then
    pass "redrive $q -> $dlq (maxReceiveCount 3)"
  else
    fail "redrive $q: $redrive"
  fi
  vt=$(echo "$attrs" | jq -r '.VisibilityTimeout')
  [ "$vt" = "30" ] && pass "visibility $q = 30" || fail "visibility $q = $vt"

  queue_arn=$(echo "$attrs" | jq -r '.QueueArn')
  for topic in $(for ty in $(echo "$types" | tr ',' ' '); do topic_of "$ty"; done | sort -u); do
    want=$(for ty in $(echo "$types" | tr ',' ' '); do [ "$(topic_of "$ty")" = "$topic" ] && echo "$ty"; done | sort | paste -sd, -)
    topic_arn=$(echo "$topics_json" | jq -r --arg t "$topic" '.Topics[].TopicArn | select(endswith(":" + $t))')
    sub_arn=$(awsl sns list-subscriptions-by-topic --topic-arn "$topic_arn" 2>/dev/null \
      | jq -r --arg e "$queue_arn" '.Subscriptions[] | select(.Endpoint == $e) | .SubscriptionArn' | head -1)
    if [ -z "$sub_arn" ]; then fail "subscription $topic -> $q missing"; continue; fi
    got=$(awsl sns get-subscription-attributes --subscription-arn "$sub_arn" \
      | jq -r '.Attributes.FilterPolicy // "{}" | fromjson | .type // [] | sort | join(",")')
    [ "$got" = "$want" ] && pass "filter $topic -> $q [$want]" || fail "filter $topic -> $q: want [$want] got [$got]"
  done
done

# --- storage and secrets -----------------------------------------------------
if awsl s3api head-bucket --bucket ingressoja-receipts >/dev/null 2>&1; then pass "bucket ingressoja-receipts"; else fail "bucket ingressoja-receipts not found"; fi
secret=$(awsl secretsmanager get-secret-value --secret-id ingressoja/auth/jwt-signing-key 2>/dev/null | jq -r '.SecretString // empty')
if [ -n "$secret" ] && echo "$secret" | jq -e '(.current.kid | type == "string" and length > 0) and (.current.privatePem | startswith("-----BEGIN"))' >/dev/null; then
  pass "secret ingressoja/auth/jwt-signing-key"
else
  fail "secret ingressoja/auth/jwt-signing-key missing or malformed"
fi

[ "$failures" -eq 0 ] || { echo "$failures check(s) failed"; exit 1; }
echo "all checks passed"
