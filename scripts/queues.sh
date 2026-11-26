#!/usr/bin/env bash
# SNS/SQS at a glance, for the presentation (docs/apresentacao/guia-demo.md §5.1).
# Usage: scripts/queues.sh                   depth of every queue and DLQ (visible / in flight)
#        scripts/queues.sh watch [SECONDS]   one line per sample with the saga queues (default: until Ctrl-C)
#        scripts/queues.sh subs              SNS topic → SQS queue subscriptions and their filter policy
#        scripts/queues.sh trail [ORDER_ID]  every message of the order: who published it and which replica
#                                            consumed it (default: the latest order)
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
. scripts/lib/aws.sh; load_env; . scripts/lib/scenario.sh
BASE_URL="${SQS_BASE_URL:-http://ministack:4566/000000000000}"
SAGA_QUEUES="inventory-service payment-service order-service notification-service user-service"

# replica_summary → "inventory 2  payment 3  order 2  user 2", marking paused (⏸) and dead (✖) replicas
replica_summary() {
  docker ps -a --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME:-ticketpro}" \
    --format '{{.Label "com.docker.compose.service"}} {{.State}}' 2>/dev/null \
    | awk -v tty="$([ -t 1 ] && echo 1)" '
        $1 ~ /^(inventory|payment|order|user)-service$/ {
          s = $1; sub(/-service$/, "", s)
          if ($2 == "running") r[s]++; else if ($2 == "paused") p[s]++; else d[s]++
        }
        END {
          split("inventory payment order user", order, " ")
          for (i = 1; i <= 4; i++) {
            s = order[i]; txt = s " " (r[s] + 0); bad = 0
            if (p[s]) { txt = txt "+" p[s] "⏸"; bad = 1 }
            if (d[s]) { txt = txt "+" d[s] "✖"; bad = 2 }
            if (tty && bad == 2) txt = "\033[1;97;41m" txt "\033[0m"
            else if (tty && bad == 1) txt = "\033[1;30;43m" txt "\033[0m"
            out = out (i > 1 ? "  " : "") txt
          }
          print out
        }'
}

# awssh → runs the sh script read from stdin in ONE aws-cli container (a container per call is too slow to watch)
awssh() {
  docker run --rm -i --init ${AWSSH_NAME:+--name "$AWSSH_NAME"} --network "$TICKETPRO_NETWORK" \
    -e AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}" -e AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}" \
    -e AWS_REGION="${AWS_REGION:-us-east-1}" -e AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://ministack:4566}" \
    -e AWS_PAGER="" -e BASE_URL="$BASE_URL" -e QUEUES="$SAGA_QUEUES" -e DUR="${DUR:-0}" \
    --entrypoint sh "$AWS_CLI_IMAGE" -s
}

case "${1:-list}" in
  list)
    printf '%-38s %9s %7s\n' FILA VISIVEIS EM_VOO
    # main queues first, then the DLQs
    awssh <<'EOF' | awk '{ print ($1 ~ /-dlq$/ ? 1 : 0) " " $0 }' | sort | cut -d' ' -f2-
for url in $(aws sqs list-queues --query 'QueueUrls[]' --output text); do
  set -- $(aws sqs get-queue-attributes --queue-url "$url" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --query 'Attributes.[ApproximateNumberOfMessages,ApproximateNumberOfMessagesNotVisible]' --output text)
  printf '%-38s %9s %7s\n' "${url##*/}" "$1" "$2"
done
EOF
    ;;
  watch)
    DUR="${2:-0}"
    # closing the window may kill the docker CLI without stopping the container: remove it on the way out
    AWSSH_NAME="ticketpro-queues-watch-$$"
    trap 'docker rm -f "$AWSSH_NAME" >/dev/null 2>&1' EXIT HUP INT TERM
    printf 'formato: fila=visíveis/em_voo (em_voo = recebida por uma réplica, ainda sem ack) │ réplicas rodando (+⏸ congeladas, +✖ mortas). Ctrl-C para parar.\n'
    awssh <<'EOF' | while IFS= read -r line; do printf '%s  │ %s\n' "$line" "$(replica_summary)"; done
end=$(( $(date +%s) + DUR ))
while [ "$DUR" = 0 ] || [ "$(date +%s)" -lt "$end" ]; do
  line="$(date +%T)"
  for q in $QUEUES; do
    v=$(aws sqs get-queue-attributes --queue-url "$BASE_URL/ticketpro-$q" \
      --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
      --query 'Attributes.[ApproximateNumberOfMessages,ApproximateNumberOfMessagesNotVisible]' --output text | tr '\t' /)
    line="$line  ${q%-service}=$v"
  done
  echo "$line"
done
EOF
    ;;
  subs)
    printf '%-22s %-34s %s\n' TOPICO FILA FILTRO
    awssh <<'EOF' | sort
for arn in $(aws sns list-subscriptions --query 'Subscriptions[].SubscriptionArn' --output text); do
  set -- $(aws sns get-subscription-attributes --subscription-arn "$arn" \
    --query 'Attributes.[TopicArn,Endpoint]' --output text)
  f=$(aws sns get-subscription-attributes --subscription-arn "$arn" --query 'Attributes.FilterPolicy' --output text)
  printf '%-22s %-34s %s\n' "${1##*:}" "${2##*:}" "$(echo "$f" | tr -d ' \n')"
done
EOF
    ;;
  trail)
    o="${2:-$(sql orders "SELECT id FROM orders ORDER BY created_at DESC LIMIT 1")}"
    [ -n "$o" ] || { echo "nenhum pedido ainda"; exit 0; }
    corr=$(sql orders "SELECT correlation_id FROM outbox WHERE payload->>'orderId' = '$o' ORDER BY id LIMIT 1")
    [ -n "$corr" ] || { echo "pedido $o sem eventos no outbox"; exit 1; }
    echo "pedido $o  correlationId $corr"
    printf '%-12s  %-24s  %-9s  %s\n' HORA REPLICA ACAO EVENTO
    docker compose logs --since 2h 2>/dev/null | grep "$corr" | grep -E '"message_(published|consumed)"' \
      | jq -Rr 'capture("^(?<c>[^ |]+) *\\| (?<j>\\{.*)$")? | .c as $c | (.j | fromjson?) // empty
          | [(.timestamp // "")[11:23], ($c | .[0:24]),
             (if .message == "message_published" then "publicou" elif .duplicate then "duplicada" else "consumiu" end),
             ((.type // "?") + " v" + ((.version // "?") | tostring))]
          | "\(.[0])  \(.[1] + (" " * (24 - (.[1] | length))))  \(.[2] + (" " * (9 - (.[2] | length))))  \(.[3])"' \
      | sort ;;
  *) echo "usage: $0 [list | watch [SECONDS] | subs | trail [ORDER_ID]]" >&2; exit 2 ;;
esac
