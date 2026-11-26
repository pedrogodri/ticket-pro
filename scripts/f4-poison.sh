#!/usr/bin/env bash
# F4 poison message (RFC 0003 §5.4): an invalid OrderCreated goes to ticketpro-inventory-service-dlq after
# maxReceiveCount deliveries, and the queue keeps flowing: a valid order right after is processed.
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh; load_env; . scripts/lib/scenario.sh
SCENARIO=f4
DLQ="http://ministack:4566/000000000000/ticketpro-inventory-service-dlq"

POISON=$(id); POISON_MSG=$(id); since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
publish ticketpro-orders OrderCreated 1 "$(envelope OrderCreated 1 "$(jq -cn --arg o "$POISON" '{orderId:$o, quantity:"two"}')" "$POISON_MSG")"
info "published an invalid OrderCreated (orderId $POISON)"
banner fail "MENSAGEM VENENOSA PUBLICADA" 'OrderCreated com quantity:"two" e sem userId, eventId e sectorId' "logo atrás vem uma mensagem válida"
GOOD=$(id)
publish ticketpro-orders OrderCreated 1 "$(envelope OrderCreated 1 "$(jq -cn --arg o "$GOOD" --arg u "$(id)" --arg e "$EVENT" --arg s "$SECTOR" \
  '{orderId:$o, userId:$u, eventId:$e, sectorId:$s, quantity:1}')")"

in_dlq() { awsl sqs receive-message --queue-url "$DLQ" --max-number-of-messages 10 --visibility-timeout 0 \
  | jq -e --arg m "$POISON_MSG" '[.Messages[]?.Body | select(contains($m))] | length > 0' >/dev/null; }
# Each peek returns a sample of at most 10, so older messages may hide it once; the loop peeks again.
moved=""
for _ in $(seq 1 60); do in_dlq && { moved=yes; break; }; sleep 0.5; done
[ -n "$moved" ] && pass "invalid message moved to ticketpro-inventory-service-dlq" || fail "invalid message not in the DLQ after 30s"
rejected=$(docker compose logs inventory-service --since "$since" 2>/dev/null | grep -c '"message_rejected"')
banner info "VENENOSA → DLQ depois de $rejected rejeição(ões)" "ticketpro-inventory-service-dlq; o consumidor não caiu e não gravou nada"
[ "$rejected" -ge 1 ] && pass "consumer logged message_rejected ($rejected)" || fail "no message_rejected log"
[ "$(sql inventory "SELECT count(*) FROM reservations WHERE order_id = '$GOOD'")" = 1 ] && pass "the next valid message was processed (queue kept flowing)" \
  || fail "valid message after the poison was not processed"
[ "$(sql inventory "SELECT count(*) FROM reservations WHERE order_id = '$POISON'")" = 0 ] && pass "the invalid message had no effect" || fail "invalid message created a reservation"
banner ok "A FILA SEGUIU: a mensagem válida seguinte foi processada" "veja a venenosa no SQS Admin (DLQ do inventory) ou: scripts/dlq.sh show inventory-service"
info "inspect with: scripts/dlq.sh show inventory-service"
finish
