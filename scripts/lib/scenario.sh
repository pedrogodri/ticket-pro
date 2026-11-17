#!/usr/bin/env bash
# Shared helpers for the failure scenarios scripts/f1..f8 (RFC 0003 §5.4–5.8). Source after aws.sh.
# Everything a client does goes through the gateway; databases and SNS are touched only to verify
# and to inject messages.

BASE="${BASE:-http://localhost:8080}"
failures=0
pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
info() { echo "info  $1"; }
die() { fail "$1"; echo "${SCENARIO:-scenario}: aborted"; exit 1; }
finish() { echo "${SCENARIO:-scenario}: $failures failed"; [ "$failures" -eq 0 ]; }
id() { uuidgen | tr '[:upper:]' '[:lower:]'; }
now_iso() { date -u +%Y-%m-%dT%H:%M:%S.000Z; }

# sql DB QUERY → rows (|-separated, no header). DB is the service database name: orders, inventory...
sql() {
  local db="$1" host
  case "$db" in
    orders) host=orders-db ;; inventory) host=inventory-db ;; payments) host=payments-db ;;
    notifications) host=notifications-db ;; users) host=users-db ;; auth) host=auth-db ;;
    *) echo "unknown database $db" >&2; return 1 ;;
  esac
  docker compose exec -T "$host" psql -U "${db}_owner" -d "$db" -tAc "$2"
}

# http METHOD PATH [BODY] [TOKEN] [extra curl args...] → STATUS, BODY. Retries 429 honouring Retry-After
# (the gateway rate limits logins per IP and orders per user).
http() {
  local method="$1" path="$2" body="${3:-}" token="${4:-}" out wait
  shift $(( $# < 4 ? $# : 4 ))
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    out="$(curl -s -D /tmp/scenario-h.$$ -w '\n%{http_code}' -X "$method" "$BASE$path" -H 'content-type: application/json' \
      ${token:+-H "Authorization: Bearer $token"} ${body:+-d "$body"} "$@")"
    STATUS="${out##*$'\n'}"
    BODY="${out%$'\n'*}"
    [ "$STATUS" = 429 ] || break
    wait="$(grep -i '^retry-after:' /tmp/scenario-h.$$ | tr -dc '0-9')"
    sleep "${wait:-1}"
  done
  rm -f /tmp/scenario-h.$$
}

# new_user → prints an access token for a freshly registered user.
new_user() {
  local email="scn-$(id)@exemplo.com"
  http POST /auth/register "{\"email\":\"$email\",\"password\":\"correct horse battery\",\"name\":\"Scenario\"}"
  [ "$STATUS" = 201 ] || { echo "register $STATUS $BODY" >&2; return 1; }
  http POST /auth/login "{\"email\":\"$email\",\"password\":\"correct horse battery\"}"
  echo "$BODY" | jq -r '.accessToken // empty'
}

# place_order TOKEN EVENT SECTOR QTY → prints the orderId (202 expected)
place_order() {
  http POST /orders "{\"eventId\":\"$2\",\"sectorId\":\"$3\",\"quantity\":$4}" "$1" -H "Idempotency-Key: $(id)"
  [ "$STATUS" = 202 ] || { echo "POST /orders $STATUS $BODY" >&2; return 1; }
  echo "$BODY" | jq -r .orderId
}

# final_state TOKEN ORDER [SECONDS=30] → prints "STATUS REASON" once final, or the last status
final_state() {
  local s=""
  for _ in $(seq 1 $(( ${3:-30} * 2 ))); do
    http GET "/orders/$2" "" "$1"
    s="$(echo "$BODY" | jq -r '"\(.status) \(.cancelReason // "")"')"
    case "$s" in CONFIRMED*|CANCELLED*) break ;; esac
    sleep 0.5
  done
  echo "$s" | sed 's/ *$//'
}

# available EVENT SECTOR TOKEN → stock left in the sector
available() {
  http GET "/events/$1" "" "$3"
  echo "$BODY" | jq --arg s "$2" '.sectors[] | select(.id == $s) | .available'
}

# publish TOPIC_NAME TYPE VERSION ENVELOPE_JSON → SNS publish with the outbox message attributes
publish() {
  awsl sns publish --topic-arn "arn:aws:sns:${AWS_REGION:-us-east-1}:000000000000:$1" --message "$4" \
    --message-attributes "{\"type\":{\"DataType\":\"String\",\"StringValue\":\"$2\"},\"version\":{\"DataType\":\"String\",\"StringValue\":\"$3\"}}" >/dev/null
}

# envelope TYPE VERSION PAYLOAD_JSON [MESSAGE_ID] → envelope JSON
envelope() {
  jq -cn --arg m "${4:-$(id)}" --arg c "$(id)" --arg t "$(now_iso)" --arg ty "$1" --argjson v "$2" --argjson p "$3" \
    '{messageId:$m, correlationId:$c, type:$ty, version:$v, occurredAt:$t, payload:$p}'
}

# set_payment VAR=VALUE... → recreate the payment replicas with overridden env (keeps 2 replicas)
set_payment() {
  env "$@" docker compose up -d --no-deps payment-service >/dev/null 2>&1
  scripts/wait-healthy.sh 120 >/dev/null || return 1
}

EVENT=0193a6f0-3333-7000-8000-000000000003   # seeded "Festival de Verão 2026"
SECTOR=0193a6f0-4444-7000-8000-000000000004  # Pista, large stock
F5_EVENT=$EVENT
F5_SECTOR=0193a6f0-4444-7000-8000-0000000000f5 # 10 units (R2/F5)
