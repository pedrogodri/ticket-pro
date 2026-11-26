#!/usr/bin/env bash
# F8 scale (R4, RFC 0003 §5.8): `--scale payment-service=3` → the 3 replicas consume the same queue,
# every order reaches a final state once and no payment is charged twice. The autoscaler is stopped
# meanwhile so it does not scale back during the run.
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh; load_env; . scripts/lib/scenario.sh
SCENARIO=f8
N=12

SCALE_BEFORE=$(docker compose ps -q payment-service | wc -l | tr -d ' ')
AUTOSCALER_WAS_UP=$(docker compose ps -q --status running autoscaler)
restore() {
  docker compose up -d --no-deps --scale payment-service="$SCALE_BEFORE" payment-service >/dev/null 2>&1
  [ -n "$AUTOSCALER_WAS_UP" ] && docker compose start autoscaler >/dev/null 2>&1
  return 0
}
trap restore EXIT
trace "docker compose stop autoscaler (para ele não desfazer a escala durante o teste)"
docker compose stop autoscaler >/dev/null 2>&1
since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
trace "docker compose up --scale payment-service=3: 3 réplicas na mesma fila"
docker compose up -d --no-deps --scale payment-service=3 payment-service >/dev/null 2>&1
# wait-healthy.sh would flag the stopped autoscaler; wait for the payment replicas only.
healthy() { [ "$(docker ps --filter label=com.docker.compose.project=ticketpro --filter label=com.docker.compose.service=payment-service --filter health=healthy -q | wc -l | tr -d ' ')" = 3 ]; }
for _ in $(seq 1 120); do healthy && break; sleep 1; done
healthy || die "payment replicas not healthy"
n=$(docker compose ps -q payment-service | wc -l | tr -d ' ')
banner info "ESCALA HORIZONTAL: payment-service com $n réplicas na mesma fila" "autoscaler parado durante o teste para não desfazer a escala"
trace_replicas payment-service
[ "$n" = 3 ] && pass "payment-service running 3 replicas" || fail "payment-service has $n replicas"

TOKENS=(); for _ in 1 2 3; do t=$(new_user) || die "could not create users"; TOKENS+=("$t"); done
ORDERS=()
for i in $(seq 1 $N); do o=$(place_order "${TOKENS[$((i % 3))]}" "$EVENT" "$SECTOR" 1) || die "order not accepted"; ORDERS+=("$o"); done
list=$(printf "'%s'," "${ORDERS[@]}"); list=${list%,}
for _ in $(seq 1 60); do
  open=$(sql orders "SELECT count(*) FROM orders WHERE id IN ($list) AND status NOT IN ('CONFIRMED','CANCELLED')")
  [ "$open" = 0 ] && break; sleep 1
done
[ "$open" = 0 ] && pass "all $N orders reached a final state" || fail "$open of $N orders still open"
charges=$(sql payments "SELECT count(*) FROM payments WHERE order_id IN ($list)")
dupes=$(sql payments "SELECT count(*) FROM (SELECT payment_id FROM provider_charges GROUP BY payment_id HAVING count(*) > 1) d")
[ "$charges" = "$N" ] && pass "one payment per order ($charges)" || fail "$charges payments for $N orders"
[ "$dupes" = 0 ] && pass "no payment charged twice" || fail "$dupes payments charged more than once"
banner ok "RESULTADO COM 3 RÉPLICAS: $charges pagamentos para $N pedidos · $dupes cobrança(s) duplicada(s)" "mensagens por réplica:"
busy=0
for c in $(docker compose ps -q payment-service); do
  k=$(docker logs --since "$since" "$c" 2>&1 | grep -c message_consumed)
  info "$(docker inspect -f '{{.Name}}' "$c" | tr -d /) consumed $k message(s)"
  [ "$k" -ge 1 ] && busy=$((busy + 1))
done
[ "$busy" -ge 2 ] && pass "$busy replicas shared the queue" || fail "only $busy replica consumed"
finish
