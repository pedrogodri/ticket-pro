#!/usr/bin/env bash
# Presentation runner (docs/runbook.md, docs/apresentacao/roteiro.md). For each scenario it states how the
# failure is provoked and what must happen BEFORE running it, runs the canonical script (scripts/f1..f8 and
# the checks) and then shows the evidence in the databases and in the logs, as the assignment asks.
# It also covers the professor's surprise failure: stop any service, buy, bring it back, show the outcome.
# Usage:
#   scripts/demo.sh                                  interactive menu
#   scripts/demo.sh f1 … f8 | happy | replicas | gateway | versioning | autoscale | payment | notification
#   scripts/demo.sh order [ORDER_ID]                 one order in every database + its logs + Jaeger link
#                                                    (default: the latest order)
#   scripts/demo.sh surprise SERVICE [SECONDS=10]    surprise failure on SERVICE (e.g. inventory-service)
#   scripts/demo.sh all                              rehearsal: everything in sequence, one line each
#   scripts/demo.sh collection [PREFIX]              Postman collection through Newman: browse folders and
#                                                    requests, or run the top folder starting with PREFIX (03)
#   scripts/demo.sh logs                             live event flow: one short line per message published,
#                                                    consumed, duplicated, rejected, broker drop, scaling
# With a terminal on stdin it waits for Enter between the prediction and the run (DEMO_NO_PAUSE=1 skips).
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh; load_env; . scripts/lib/scenario.sh
SCENARIO=demo

if [ -t 1 ]; then B=$'\033[1m'; D=$'\033[2m'; G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; N=$'\033[0m'
else B=""; D=""; G=""; Y=""; R=""; N=""; fi
SERVICES="order-service inventory-service payment-service notification-service user-service auth-service gateway"
JAEGER="${JAEGER_URL:-http://localhost:16686}"

title() { printf '\n%s━━ %s ━━%s\n' "$B" "$1" "$N"; }
section() { printf '\n%s▸ %s%s\n' "$Y" "$1" "$N"; }
pause() { [ -t 0 ] && [ -z "${DEMO_NO_PAUSE:-}" ] && { printf '%s[Enter para provocar]%s ' "$D" "$N"; read -r _; }; return 0; }

# predict HOW EXPECTED → the prediction shown before anything runs
predict() {
  printf '%sComo provocar:%s %s\n' "$B" "$N" "$1"
  printf '%sResultado esperado:%s %s\n' "$B" "$N" "$2"
}

# run_script CMD... → runs a canonical scenario script and reports its verdict
run_script() {
  section "executando: $*"
  "$@"; local rc=$?
  [ "$rc" = 0 ] && printf '%s✔ cenário passou%s\n' "$G" "$N" || printf '%s✘ cenário falhou (exit %s)%s\n' "$R" "$rc" "$N"
  return "$rc"
}

latest_order() { sql orders "SELECT id FROM orders ORDER BY created_at DESC LIMIT 1"; }

# logs_for SINCE PATTERN → the business log lines matching PATTERN, one per line, ordered by time
logs_for() {
  # shellcheck disable=SC2086
  docker compose logs --since "$1" $SERVICES 2>/dev/null | grep -E "$2" \
    | jq -Rr 'capture("^(?<c>[^ |]+) *\\| (?<j>\\{.*)$")? | .c as $c | (.j | fromjson?) // empty
        | select((.message // "") | test("^[a-z_]+$")) | select(.message | test("^(message_published|broker_up|broker_down)$") | not)
        | [(.timestamp // "")[11:23], ($c | .[0:24]), .message, (.type // ""), (.reason // .cancelReason // ""),
           (if .duplicate then "duplicate=true" else "" end)] | join("  ")' \
    | sort | sed 's/  *$//'
}

# --- Postman collection (docs/postman) through Newman -------------------------------------------------
COLLECTION=docs/postman/ticket-pro.postman_collection.json
COLLECTION_ENV=docs/postman/ticket-pro.local.postman_environment.json

# newman_cmd → how to call Newman here (global install or the npx cache), or fails with the install hint
newman_cmd() {
  if command -v newman >/dev/null; then echo newman
  elif npx --no-install newman --version >/dev/null 2>&1; then echo "npx --no-install newman"
  else
    printf '%sNewman não está instalado.%s Instale uma vez (pacote oficial da Postman) e rode de novo:\n  npm install -g newman@6\n' "$R" "$N" >&2
    return 1
  fi
}

# item_path JSON_INDEXES → the jq path to that item, e.g. [3,0] → ["item",3,"item",0]
# shellcheck disable=SC2016  # $p and $i are jq variables
COLL_JQ_DEFS='def ipath($p): reduce $p[] as $i ([]; . + ["item", $i]);
  def pick($p): if ($p | length) == 0 then . else .item = [ .item[$p[0]] | pick($p[1:]) ] end;'

# run_collection INDEXES → runs that folder or request (with "00 · Setup" before it, which creates the
# users and the token every other request needs) and prints every request, its status and its tests
# shellcheck disable=SC2016  # $p and $i are jq variables
run_collection() {
  local p="$1" nm tmp label rc depth top name f skipped
  label=$(jq -r --argjson p "$p" "$COLL_JQ_DEFS"' [foreach $p[] as $i (.; .item[$i]; .name)] | join(" / ")' "$COLLECTION")
  depth=$(jq 'length' <<<"$p")
  top=$(jq -r --argjson p "$p" 'if ($p | length) > 0 then .item[$p[0]].name[0:2] else "" end' "$COLLECTION")
  name=$(jq -r --argjson p "$p" "$COLL_JQ_DEFS"' if ($p | length) > 0 then getpath(ipath($p)) | .name else "" end' "$COLLECTION")
  # 05 (F1–F8) and 07 (per-service checks) need docker to provoke the failure: run the real scenario
  if [ "$top" = 05 ] && [ "$depth" -le 2 ]; then
    if [ "$depth" = 1 ]; then for f in f1 f2 f3 f4 f5 f6 f7 f8; do scenario "$f"; done; return; fi
    f=$(grep -oE '^F[1-8]' <<<"$name" | tr F f)
    info "$label: a falha de infraestrutura precisa de docker, rodando o cenário real ($f)"
    scenario "$f"; return
  fi
  if [ "$top" = 07 ] && [ "$depth" -le 2 ]; then
    case "$name" in
      *Payment*) scenario payment ;; *Notification*) scenario notification ;; *Gateway*) scenario gateway ;;
      *) scenario payment; scenario notification; scenario gateway ;;
    esac
    return
  fi
  nm=$(newman_cmd) || return 1
  tmp=$(mktemp -t ticketpro-collection).json
  # ⏳ = not implemented yet (auth refresh/logout): dropped from folder runs, kept when picked directly.
  # The whole collection leaves out 05 and 07, which run as scripts (pick the folder for that).
  jq --argjson p "$p" "$COLL_JQ_DEFS"'
    (if ($p | length) == 0 or $p[0] == 0 then pick($p)
     else .item[0] as $setup | pick($p) | .item = [$setup] + .item end)
    | if ($p | length) == 0 then .item |= map(select(.name | test("^0[57] ") | not)) else . end' "$COLLECTION" > "$tmp.full"
  if [[ "$name" == ⏳* ]]; then cp "$tmp.full" "$tmp"
  else jq 'def drop: if has("item") then .item |= map(select(.name | startswith("⏳") | not) | drop) else . end; drop' "$tmp.full" > "$tmp"; fi
  skipped=$(( $(jq '[.. | objects | select(has("request"))] | length' "$tmp.full") - $(jq '[.. | objects | select(has("request"))] | length' "$tmp") ))
  rm -f "$tmp.full"
  title "Collection: ${label:-inteira}"
  [ "$skipped" -gt 0 ] && info "$skipped request(s) ⏳ puladas (refresh/logout ainda não implementados no auth); escolha uma delas direto para rodar"
  [ "$depth" = 0 ] && info "05 (F1–F8) e 07 (checagens) rodam como scripts: escolha a pasta no menu"
  [ "${p:0:2}" = "[0" ] || [ "$p" = "[]" ] || info "roda antes: 00 · Setup (cria os usuários A e B, o token e descobre o evento do seed)"
  [ "$(jq --argjson p "$p" "$COLL_JQ_DEFS"' ($p | length) > 0 and (getpath(ipath($p)) | has("request"))' "$COLLECTION")" = true ] &&
    info "request avulsa: se ela usa algo de uma request anterior da mesma pasta (ex.: o orderId do POST), rode a pasta (opção 0)"
  case "$label" in "05 ·"*) info "pela collection só os passos HTTP; a falha de infraestrutura é provocada pelos f1…f8 do menu principal" ;; esac
  section "newman run (delay de 500 ms entre requests: o gateway limita pedidos a 2/s por usuário)"
  # shellcheck disable=SC2086
  $nm run "$tmp" -e "$COLLECTION_ENV" --delay-request 500 --color on; rc=$?
  rm -f "$tmp"
  [ "$rc" = 0 ] && printf '%s✔ collection passou%s\n' "$G" "$N" || printf '%s✘ collection com falhas (exit %s)%s\n' "$R" "$rc" "$N"
  return "$rc"
}

# collection_menu [PREFIX] → browse the collection tree; with PREFIX, run the top folder whose name starts with it
# shellcheck disable=SC2016  # $p and $i are jq variables
collection_menu() {
  local path='[]' c n kids is_folder next top
  if [ -n "${1:-}" ]; then
    n=$(jq --arg x "$1" '[.item[].name] | to_entries | map(select(.value | startswith($x))) | .[0].key // empty' "$COLLECTION")
    [ -n "$n" ] || { echo "nenhuma pasta começa com '$1'" >&2; return 2; }
    run_collection "[$n]"; return
  fi
  while :; do
    title "Collection Postman: $(jq -r --argjson p "$path" "$COLL_JQ_DEFS"' if ($p | length) == 0 then "raiz" else [foreach $p[] as $i (.; .item[$i]; .name)] | join(" / ") end' "$COLLECTION")"
    jq -r --argjson p "$path" "$COLL_JQ_DEFS"' (if ($p | length) == 1 then .item[$p[0]].name[0:2] else "" end) as $top
      | getpath(ipath($p) + ["item"]) | to_entries[]
      | "  \(.key + 1)) \(.value.name)\(if ($top == "05" or $top == "07") then "  ▶ roda o cenário real"
          elif .value.item then "  ▸ \(.value.item | length) itens" else "" end)"' "$COLLECTION"
    printf '\n  0) rodar %s   v) voltar   q) sair\n> ' "$([ "$path" = '[]' ] && echo 'a collection inteira' || echo 'tudo daqui')"
    read -r c || return 0
    case "$c" in
      q) return 0 ;;
      v) [ "$path" = '[]' ] && return 0; path=$(jq -c '.[:-1]' <<<"$path") ;;
      0) run_collection "$path" ;;
      ''|*[!0-9]*) echo "opção inválida" ;;
      *)
        kids=$(jq --argjson p "$path" "$COLL_JQ_DEFS"' getpath(ipath($p) + ["item"]) | length' "$COLLECTION")
        [ "$c" -ge 1 ] && [ "$c" -le "$kids" ] || { echo "opção inválida"; continue; }
        is_folder=$(jq --argjson p "$path" --argjson i $((c - 1)) "$COLL_JQ_DEFS"' getpath(ipath($p) + ["item", $i]) | has("item")' "$COLLECTION")
        next=$(jq -c --argjson i $((c - 1)) '. + [$i]' <<<"$path")
        top=$(jq -r --argjson p "$next" '.item[$p[0]].name[0:2]' "$COLLECTION")
        # an F folder (05) or a per-service check (07) runs the real scenario instead of opening the folder
        if [ "$is_folder" = true ] && { [ "$top" = 05 ] || [ "$top" = 07 ]; } && [ "$(jq length <<<"$next")" = 2 ]; then
          run_collection "$next"
        elif [ "$is_folder" = true ]; then path="$next"
        else run_collection "$next"; fi ;;
    esac
  done
}

# live_logs → the asynchronous side as it happens (T2 in the presentation layout): one line per message a
# service publishes or consumes, plus duplicates, rejections, broker drops and real scaling decisions
live_logs() {
  local ev
  printf '%sfluxo de eventos ao vivo + containers que morrem, congelam ou sobem (Ctrl-C para parar)%s\n' "$D" "$N"
  # Docker side: a container dying, freezing or starting is the moment the audience must see (F2, F6, F8,
  # autoscale, surprise). Runs beside the log stream; killed on the way out (it ignores Ctrl-C in background).
  ( docker events --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME:-ticketpro}" --filter type=container \
      --filter event=die --filter event=start --filter event=pause --filter event=unpause \
      --format '{{.Time}}|{{.Action}}|{{.Actor.Attributes.name}}|{{index .Actor.Attributes "exitCode"}}' 2>/dev/null \
    | while IFS='|' read -r t action name code; do
        name=${name#"${COMPOSE_PROJECT_NAME:-ticketpro}"-}
        case "$action" in
          die) if [ "$code" = 137 ]; then msg="✖ MORREU (kill)"; else msg="■ PAROU (exit $code)"; fi; c=$'\033[1;97;41m' ;;
          start) msg="▲ SUBIU"; c=$'\033[1;30;42m' ;;
          pause) msg="⏸ CONGELADO (docker pause)"; c=$'\033[1;30;43m' ;;
          unpause) msg="▶ DESCONGELADO"; c=$'\033[1;30;42m' ;;
          *) continue ;;
        esac
        [ -t 1 ] || c=""
        printf '%s %s      %-24s  %s %s\n' "$c" "$(date -u -r "$t" +%H:%M:%S)" "$name" "$msg" "${c:+$N}"
      done ) &
  ev=$!
  trap 'pkill -P "$ev" 2>/dev/null; kill "$ev" 2>/dev/null' EXIT INT TERM HUP
  # shellcheck disable=SC2086
  docker compose logs -f --since 1s --no-color $SERVICES autoscaler 2>/dev/null \
    | grep --line-buffered -E '"message":"(message_published|message_consumed|message_rejected|broker_down|broker_up|scale_decision|stale_event|compensation_applied|late_payment_refund|stock_released)"' \
    | jq --unbuffered -Rr --arg y "$Y" --arg r "$R" --arg g "$G" --arg d "$D" --arg n "$N" --arg m "$([ -t 1 ] && printf '\033[35m')" '
        capture("^(?<c>[^ |]+) *\\| (?<j>\\{.*)$")? | .c as $c | (.j | fromjson?) // empty
        | select(.message != "scale_decision" or .current != .desired)
        | (.type // "") as $t | ((.version // "") | tostring) as $v
        | (if .message == "message_published" then [$d, "publicou   \($t) v\($v)"]
           elif .message == "message_consumed" and .duplicate then [$y, "DUPLICADA  \($t) v\($v): ignorada (processed_messages)"]
           elif .message == "message_consumed" then ["", "consumiu   \($t) v\($v)"]
           elif .message == "message_rejected" then [$r, "REJEITADA  \($t) v\($v): \(.reason // .error // "inválida" | tostring | .[0:60])"]
           elif .message == "broker_down" then [$r, "BROKER FORA: \(.error // "" | tostring | .[0:60])"]
           elif .message == "broker_up" then [$g, "broker de volta"]
           elif .message == "scale_decision" then [$y, "ESCALA \(.target): fila \(.depth), réplicas \(.current) → \(.desired)"]
           elif .message == "stale_event" then [$y, "fora de ordem/atrasado \($t): UPDATE condicional não aplicou"]
           elif .message == "compensation_applied" then [$m, "COMPENSAÇÃO: pedido cancelado (\(.reason // "?")\(if $t != "" then ", por \($t)" else ", prazo do pagamento estourou" end)) → OrderCancelled"]
           elif .message == "late_payment_refund" then [$m, "APROVAÇÃO ATRASADA \($t): pedido já cancelado → pede estorno (RefundRequested)"]
           elif .message == "stock_released" then [$m, "ESTOQUE DEVOLVIDO: reserva liberada por \($t)"]
           else [$y, .message] end) as $m
        | "\($m[0])\((.timestamp // "")[11:23])  \($c | .[0:24] | . + (" " * (24 - length)))  \($m[1])\($n)"'
}

# order_evidence [ORDER_ID] → the order as every service sees it, its log trail and its trace
order_evidence() {
  local _TRACE_QUIET=1
  local o="${1:-$(latest_order)}" corr tp
  [ -n "$o" ] || { info "nenhum pedido ainda"; return 0; }
  title "Evidência do pedido $o"
  section "orders-db (order-service): estado da saga"
  table orders "SELECT status, cancel_reason, refund_status, quantity, total_cents,
    created_at::time(0) AS criado, updated_at::time(0) AS atualizado FROM orders WHERE id = '$o'"
  section "orders-db: outbox (o que o order publicou)"
  table orders "SELECT type, published_at IS NOT NULL AS publicado, attempts FROM outbox
    WHERE payload->>'orderId' = '$o' ORDER BY id"
  section "inventory-db (inventory-service): reserva, estoque e ingressos"
  table inventory "SELECT r.status, r.quantity, s.name AS setor, s.available AS disponivel_no_setor,
    (SELECT count(*) FROM tickets t WHERE t.reservation_id = r.id) AS ingressos
    FROM reservations r JOIN sectors s ON s.id = r.sector_id WHERE r.order_id = '$o'"
  section "payments-db (payment-service): cobrança e estorno"
  table payments "SELECT p.status, p.failure_reason, p.amount_cents, (c.payment_id IS NOT NULL) AS cobrado,
    c.result AS provedor, (r.id IS NOT NULL) AS estornado
    FROM payments p LEFT JOIN provider_charges c ON c.payment_id = p.id LEFT JOIN refunds r ON r.payment_id = p.id
    WHERE p.order_id = '$o'"
  section "notifications-db (notification-service) e users-db (user-service, read model)"
  table notifications "SELECT kind, created_at::time(0) AS quando FROM notifications WHERE order_id = '$o'"
  table users "SELECT (SELECT count(*) FROM my_orders WHERE order_id = '$o') AS em_my_orders,
    (SELECT count(*) FROM my_tickets WHERE order_id = '$o') AS em_my_tickets"
  corr=$(sql orders "SELECT correlation_id FROM outbox WHERE payload->>'orderId' = '$o' ORDER BY id LIMIT 1")
  tp=$(sql orders "SELECT traceparent FROM outbox WHERE payload->>'orderId' = '$o' ORDER BY id LIMIT 1")
  section "logs do pedido (orderId ou correlationId), em ordem"
  logs_for 30m "$o${corr:+|$corr}" | tail -40
  [ -n "$tp" ] && printf '\n%sTrace no Jaeger:%s %s/trace/%s\n' "$B" "$N" "$JAEGER" "$(echo "$tp" | cut -d- -f2)"
}

# inventory_evidence SINCE → what the inventory consumer did with the injected messages
inventory_evidence() {
  title "Evidência no inventory"
  section "inventory-db: últimas reservas"
  table inventory "SELECT order_id, status, quantity, created_at::time(0) AS criada FROM reservations
    ORDER BY created_at DESC LIMIT 8"
  section "inventory-db: processed_messages (R1, idempotência)"
  table inventory "SELECT count(*) AS mensagens_processadas,
    count(*) FILTER (WHERE processed_at > now() - interval '5 minutes') AS nos_ultimos_5_min FROM processed_messages"
  section "DLQ do inventory"
  scripts/dlq.sh list 2>/dev/null | grep -E 'inventory|queue' || true
  section "logs do inventory no cenário"
  logs_for "$1" 'inventory-service' | grep -E 'duplicate=true|message_rejected|stock_|tickets_issued|stale_event' | tail -25
}

f5_evidence() {
  title "Evidência do F5 (R2: zero venda dupla)"
  section "orders-db: pedidos no setor F5 nos últimos 5 min"
  table orders "SELECT status, cancel_reason, count(*) FROM orders
    WHERE sector_id = '$F5_SECTOR' AND created_at > now() - interval '5 minutes' GROUP BY 1, 2 ORDER BY 1"
  section "inventory-db: setor F5 e reservas"
  table inventory "SELECT name, capacity, available FROM sectors WHERE id = '$F5_SECTOR'"
  table inventory "SELECT status, count(*), sum(quantity) AS unidades FROM reservations
    WHERE sector_id = '$F5_SECTOR' AND created_at > now() - interval '5 minutes' GROUP BY 1"
}

f8_evidence() {
  title "Evidência do F8"
  section "réplicas do payment agora"
  docker compose ps payment-service --format 'table {{.Name}}\t{{.Status}}'
  section "payments-db: um pagamento e no máximo uma cobrança por pedido"
  table payments "SELECT count(*) AS pagamentos, count(DISTINCT order_id) AS pedidos,
    (SELECT count(*) FROM (SELECT payment_id FROM provider_charges GROUP BY 1 HAVING count(*) > 1) d) AS cobrados_2x
    FROM payments WHERE created_at > now() - interval '5 minutes'"
}

outbox_evidence() {
  section "orders-db: linhas do outbox ainda não publicadas (tem que ser 0)"
  table orders "SELECT count(*) AS pendentes FROM outbox WHERE published_at IS NULL"
}

scenario() {
  local since rc; since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  case "$1" in
    happy)
      title "Caminho feliz (R3: 202 + consulta de status)"
      predict "cadastro → login → catálogo → POST /orders → polling de GET /orders/{id}" \
        "202 com id; o pedido chega a CONFIRMED; ingressos em /me/tickets; comprovante no S3; mesma Idempotency-Key devolve o mesmo pedido"
      pause; run_script scripts/happy-path.sh; rc=$?; order_evidence ;;
    replicas)
      title "R4: consumidores concorrentes"
      predict "order, inventory e payment com 2 réplicas cada na mesma fila SQS" \
        "toda réplica consome mensagens; nenhum efeito duplicado (idempotência por messageId)"
      pause; run_script scripts/check-replicas.sh 3; rc=$? ;;
    f1)
      title "F1: compensação"
      predict "PAYMENT_FAILURE_RATE=1 no payment (réplicas recriadas com a env) e um pedido de 2 unidades" \
        "PaymentFailed → pedido CANCELLED(PAYMENT_FAILED) → OrderCancelled → reserva RELEASED e estoque devolvido; o notification registra ORDER_CANCELLED (cliente notificado)"
      pause; run_script scripts/f1-compensation.sh; rc=$?; order_evidence ;;
    f2)
      title "F2: serviço morre no meio"
      predict "20 OrderCreated com uma réplica do inventory congelada segurando mensagens; docker kill nela" \
        "o que ela não commitou não teve ack: após o visibility timeout a outra réplica processa; cada pedido com exatamente 1 reserva"
      pause; run_script scripts/f2-consumer-kill.sh; rc=$?; inventory_evidence "$since" ;;
    f3)
      title "F3: mensagem duplicada"
      predict "o mesmo envelope OrderCreated (mesmo messageId) publicado 5 vezes no SNS" \
        "1 reserva e 1 linha em processed_messages; as outras 4 entregas logadas como duplicate=true"
      pause; run_script scripts/f3-duplicate.sh; rc=$?; inventory_evidence "$since" ;;
    f4)
      title "F4: mensagem venenosa"
      predict "OrderCreated com payload inválido (quantity:\"two\", sem campos) e logo depois um válido" \
        "a inválida é recebida maxReceiveCount vezes e vai para a DLQ sem efeito; a válida é processada normalmente"
      pause; run_script scripts/f4-poison.sh; rc=$?; inventory_evidence "$since" ;;
    f5)
      title "F5: corrida pelo recurso"
      predict "100 POST /orders simultâneos (20 usuários × 5) no setor F5, que tem 10 unidades" \
        "exatamente 10 CONFIRMED, 90 CANCELLED(OUT_OF_STOCK), available = 0 e nunca negativo (UPDATE condicional + CHECK)"
      pause; run_script scripts/f5-race.sh; rc=$?; f5_evidence ;;
    f6)
      title "F6: broker fora do ar"
      predict "docker pause ministack por 30 s, com um pedido criado no meio" \
        "POST /orders segue 202; o pedido fica PENDING e o evento espera no outbox; ao despausar os serviços reconectam sozinhos, o outbox drena e a saga termina"
      pause; run_script scripts/f6-broker-down.sh; rc=$?; order_evidence; outbox_evidence ;;
    f7)
      title "F7: resposta tardia"
      predict "PAYMENT_LATENCY_MS (20 s) maior que o prazo do pedido ORDER_PAYMENT_TIMEOUT_MS (15 s)" \
        "no prazo o pedido vira CANCELLED(PAYMENT_TIMEOUT) e o estoque volta; a aprovação que chega atrasada vira RefundRequested → PaymentRefunded (refund_status REFUNDED)"
      pause; run_script scripts/f7-late-response.sh; rc=$?; order_evidence ;;
    f8)
      title "F8: escala horizontal"
      predict "docker compose up --scale payment-service=3 e 12 pedidos" \
        "as 3 réplicas dividem a fila; todos os pedidos terminam; 1 pagamento e no máximo 1 cobrança por pedido"
      pause; run_script scripts/f8-scale.sh; rc=$?; f8_evidence ;;
    gateway)
      title "Bônus/segurança: revogação e rate limit no gateway"
      predict "TokenRevoked publicado para o jti de um token válido; logins repetidos" \
        "o token revogado passa a receber 401; logins em rajada recebem 429 com Retry-After"
      pause; run_script scripts/gateway-check.sh; rc=$? ;;
    versioning)
      title "Bônus: versionamento de eventos"
      predict "OrderCreated v1, v2 e v3 publicados no tópico" \
        "v1 é convertida (upcaster, channel API) e v2 processada; v3 é desconhecida e vai para a DLQ sem efeito"
      pause; run_script scripts/demo-versioning.sh; rc=$? ;;
    autoscale)
      title "Bônus: autoescala pelo tamanho da fila"
      predict "40 PaymentRequested acumulados na fila do payment" \
        "o autoscaler sobe réplicas até AUTOSCALER_MAX_REPLICAS, a fila esvazia e, após o cooldown, volta a 2"
      pause; run_script scripts/demo-autoscale.sh; rc=$? ;;
    payment)
      title "Payment sozinho (R5, R1)"
      predict "eventos do order injetados direto: duplicatas, estorno, falha 100% e timeout duro" \
        "1 cobrança por pedido, 1 estorno, DECLINED com taxa 1, PROVIDER_TIMEOUT sem cobrança"
      pause; run_script scripts/payment-check.sh; rc=$? ;;
    notification)
      title "Notification sozinho (S3, R1)"
      predict "TicketsIssued e OrderCancelled publicados 5 vezes cada" \
        "1 comprovante no S3 e 1 notificação por pedido; 4 duplicatas ignoradas"
      pause; run_script scripts/notification-check.sh; rc=$? ;;
    *) echo "cenário desconhecido: $1" >&2; return 2 ;;
  esac
  return "$rc"
}

# surprise_prediction SERVICE → what we expect before stopping it
surprise_prediction() {
  case "$1" in
    gateway) echo "o cliente recebe conexão recusada (é a única porta pública); lá dentro nada se perde, as sagas em andamento seguem por eventos; ao voltar, aceita pedidos de novo" ;;
    auth-service) echo "cadastro e login falham (502), mas quem já tem token continua comprando: o gateway valida o JWT localmente, com o JWKS em cache" ;;
    order-service) echo "POST /orders e GET /orders falham (502) com as duas réplicas fora; as respostas de inventory e payment esperam na fila SQS; ao voltar, nada se perde" ;;
    inventory-service) echo "o pedido é aceito (202) e fica PENDING: o OrderCreated espera na fila do inventory; ao voltar, reserva e a saga termina sozinha" ;;
    payment-service) echo "o pedido chega a AWAITING_PAYMENT e espera; fora por menos que ORDER_PAYMENT_TIMEOUT_MS (15 s) ele confirma ao voltar; por mais, o order cancela por PAYMENT_TIMEOUT, devolve o estoque, e a aprovação atrasada vira estorno (F7)" ;;
    notification-service) echo "a compra confirma normalmente; o comprovante no S3 só aparece quando ele volta (TicketsIssued espera na fila)" ;;
    user-service) echo "/me e /me/tickets falham (502); a compra confirma; ao voltar, a projeção alcança e os ingressos aparecem" ;;
    autoscaler) echo "nada muda no fluxo de compra; só para de ajustar réplicas pela fila" ;;
    *-db) echo "o serviço dono do banco falha nas escritas e o ready dá 503; consumidores não dão ack, as mensagens voltam após o visibility timeout e, ao voltar o banco, são processadas uma única vez" ;;
    *) echo "sem previsão cadastrada: descreva antes de executar" ;;
  esac
}

surprise() {
  local svc="$1" secs="${2:-10}" token order="" since
  [ "$svc" = ministack ] && { info "MiniStack guarda filas em memória: a falha dele é o F6 (pause), rodando o F6"; scenario f6; return; }
  docker compose config --services | grep -x "$svc" >/dev/null || { echo "serviço desconhecido: $svc" >&2; return 2; }
  title "Falha surpresa: $svc fora por ${secs}s"
  predict "docker compose kill $svc (todas as réplicas), compra durante a queda, depois up -d" "$(surprise_prediction "$svc")"
  token=$(new_user) || { fail "não consegui criar o usuário antes da queda"; return 1; }
  pause
  since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  trace "docker compose kill $svc (todas as réplicas)"
  docker compose kill "$svc" >/dev/null 2>&1 && info "$svc derrubado"
  banner fail "SERVIÇO DERRUBADO: $svc (todas as réplicas, docker compose kill)" "previsão: $(surprise_prediction "$svc")"
  trace_replicas "$svc"
  http POST /orders "{\"eventId\":\"$EVENT\",\"sectorId\":\"$SECTOR\",\"quantity\":1}" "$token" -H "Idempotency-Key: $(id)" --max-time 5
  info "POST /orders durante a queda → HTTP ${STATUS:-000}"
  [ "$STATUS" = 202 ] && order=$(echo "$BODY" | jq -r .orderId)
  for _ in $(seq 1 $(( secs / 2 ))); do
    [ -n "$order" ] && info "pedido $order: $(sql orders "SELECT status || coalesce('(' || cancel_reason || ')', '') FROM orders WHERE id = '$order'" 2>/dev/null || echo '?')"
    sleep 2
  done
  trace "docker compose up -d $svc"
  docker compose up -d --no-deps "$svc" >/dev/null 2>&1 && info "$svc de volta, esperando ficar healthy"
  scripts/wait-healthy.sh 180 >/dev/null || fail "a stack não voltou healthy"
  banner ok "$svc DE VOLTA E HEALTHY" "ficou fora ~${secs}s; ninguém mexeu nas filas nem nos bancos"
  trace_replicas "$svc"
  if [ -z "$order" ]; then
    order=$(place_order "$token" "$EVENT" "$SECTOR" 1) && info "pedido feito depois da volta: $order"
  fi
  if [ -n "$order" ]; then
    local final; final=$(final_state "$token" "$order" 60)
    info "estado final: $final"
    banner ok "DESFECHO DO PEDIDO $order: $final" "evidência nos bancos e nos logs logo abaixo"
  fi
  [ -n "$order" ] && order_evidence "$order"
  section "logs do $svc desde a queda"
  logs_for "$since" "$svc" | tail -10
}

all() {
  local s total=0 bad=0 out
  export DEMO_NO_PAUSE=1
  for s in happy replicas f1 f2 f3 f4 f5 f6 f7 f8 gateway versioning autoscale; do
    total=$((total + 1))
    out=$(scenario "$s" 2>&1) && printf '%s✔%s %s\n' "$G" "$N" "$s" \
      || { bad=$((bad + 1)); printf '%s✘%s %s\n' "$R" "$N" "$s"; echo "$out" | grep -E '^FAIL' | sed 's/^/    /'; }
    scripts/wait-healthy.sh 180 >/dev/null || { echo "stack not healthy after $s"; return 1; }
  done
  echo "demo all: $bad of $total failed"
  [ "$bad" = 0 ]
}

menu() {
  local c
  while :; do
    title "Ticket Pro: demonstração"
    cat <<EOF
  0) caminho feliz (R3)          f1) compensação           f5) corrida 100/10 (R2)
  r) réplicas na mesma fila (R4) f2) serviço morre no meio  f6) broker fora do ar (R6)
  o) evidência do último pedido  f3) mensagem duplicada (R1) f7) resposta tardia (R5)
  s) falha surpresa              f4) mensagem venenosa      f8) escala horizontal (R4)
  g) gateway (revogação, 429)    v) versionamento   a) autoscale   p) payment   n) notification
  c) collection Postman (pastas e requests, via Newman)
  q) sair
EOF
    printf '> '; read -r c || return 0
    case "$c" in
      0) scenario happy ;; r) scenario replicas ;; o) order_evidence ;;
      f[1-8]) scenario "$c" ;; g) scenario gateway ;; v) scenario versioning ;; a) scenario autoscale ;;
      p) scenario payment ;; n) scenario notification ;; c) collection_menu ;;
      s) printf 'serviço (%s, ministack, <db>): ' "$SERVICES"; read -r svc; printf 'segundos fora [10]: '; read -r sec
         surprise "$svc" "${sec:-10}" ;;
      q) return 0 ;;
      *) echo "opção inválida" ;;
    esac
  done
}

case "${1:-menu}" in
  menu) menu ;;
  all) all ;;
  order) order_evidence "${2:-}" ;;
  logs) live_logs ;;
  collection) collection_menu "${2:-}" ;;
  surprise) [ -n "${2:-}" ] || { echo "usage: scripts/demo.sh surprise SERVICE [SECONDS]" >&2; exit 2; }; surprise "$2" "${3:-10}" ;;
  *) scenario "$1" ;;
esac
