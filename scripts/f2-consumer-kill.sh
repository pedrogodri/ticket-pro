#!/usr/bin/env bash
# F2 consumer kill (RFC 0003 §5.4): `docker kill` on an inventory replica with messages in flight. Nothing
# it had not committed was acked, so after the visibility timeout the other replica gets those messages:
# every order ends with exactly one reservation.
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh; load_env; . scripts/lib/scenario.sh
SCENARIO=f2
N=20

QUEUE="http://ministack:4566/000000000000/ticketpro-inventory-service"
VICTIM=$(docker compose ps -q inventory-service | head -1)
trap 'docker unpause "$VICTIM" >/dev/null 2>&1; docker compose up -d --no-deps inventory-service >/dev/null 2>&1' EXIT
# Paused, the victim keeps its open long poll: SQS hands it messages it can never process (in flight).
docker pause "$VICTIM" >/dev/null || die "could not pause $VICTIM"
ORDERS=()
entries="[]"
for i in $(seq 1 $N); do
  o=$(id); ORDERS+=("$o")
  env=$(envelope OrderCreated 1 "$(jq -cn --arg o "$o" --arg u "$(id)" --arg e "$EVENT" --arg s "$SECTOR" '{orderId:$o, userId:$u, eventId:$e, sectorId:$s, quantity:1}')")
  entries=$(echo "$entries" | jq -c --arg i "m$i" --arg m "$env" '. + [{Id:$i, Message:$m,
    MessageAttributes:{type:{DataType:"String",StringValue:"OrderCreated"}, version:{DataType:"String",StringValue:"1"}}}]')
  if [ $((i % 10)) -eq 0 ]; then
    awsl sns publish-batch --topic-arn "arn:aws:sns:${AWS_REGION:-us-east-1}:000000000000:ticketpro-orders" \
      --publish-batch-request-entries "$entries" >/dev/null || die "publish failed"
    entries="[]"
  fi
done
sleep 3
in_flight=$(awsl sqs get-queue-attributes --queue-url "$QUEUE" --attribute-names ApproximateNumberOfMessagesNotVisible \
  | jq -r '.Attributes.ApproximateNumberOfMessagesNotVisible')
docker kill "$VICTIM" >/dev/null && info "published $N OrderCreated and killed $(docker inspect -f '{{.Name}}' "$VICTIM" | tr -d /)"
[ "${in_flight:-0}" -ge 1 ] && pass "$in_flight message(s) were in flight on the killed replica" \
  || fail "no message in flight when the replica was killed: the scenario proved nothing"

list=$(printf "'%s'," "${ORDERS[@]}"); list=${list%,}
done_count=0
for _ in $(seq 1 120); do
  done_count=$(sql inventory "SELECT count(DISTINCT order_id) FROM reservations WHERE order_id IN ($list)")
  [ "$done_count" = "$N" ] && break; sleep 1
done
dup=$(sql inventory "SELECT count(*) - count(DISTINCT order_id) FROM reservations WHERE order_id IN ($list)")
[ "$done_count" = "$N" ] && pass "all $N orders reserved after the kill (the other replica took over)" || fail "$done_count of $N orders reserved after 120s"
[ "$dup" = 0 ] && pass "no order reserved twice" || fail "$dup duplicate reservations"
finish
