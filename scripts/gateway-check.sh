#!/usr/bin/env bash
# Gateway checkpoint (RFC 0002 §5.5, ADR-0014): revocation via TokenRevoked and rate limit, live.
#   register → login → GET /me 200 → publish TokenRevoked(jti) to ticketpro-auth → GET /me 401
#   → repeated wrong logins hit 429 with Retry-After.
# Usage: scripts/gateway-check.sh [base_url=http://localhost:8080]
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh
load_env

BASE="${1:-http://localhost:8080}"
failures=0
pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
die() { fail "$1"; echo "gateway-check: aborted"; exit 1; }
uuid() { uuidgen | tr '[:upper:]' '[:lower:]'; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

EMAIL="gw-$(uuid)@exemplo.com"
code=$(status -X POST "$BASE/auth/register" -H 'content-type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"correct horse battery\",\"name\":\"Gateway Check\"}")
[ "$code" = 201 ] && pass "register → 201" || die "register → $code"
TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'content-type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"correct horse battery\"}" | jq -r '.accessToken // empty')
[ -n "$TOKEN" ] && pass "login → token" || die "login failed"

# The JWT payload carries jti, sub and exp; base64url → base64 with padding.
claims() { local p; p=$(echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+'); while [ $(( ${#p} % 4 )) -ne 0 ]; do p="$p="; done; echo "$p" | base64 -d; }
JTI=$(claims | jq -r .jti); SUB=$(claims | jq -r .sub); EXP=$(claims | jq -r .exp)

# The profile is projected from UserRegistered (eventually consistent): wait for it.
code=""
for _ in $(seq 1 30); do
  code=$(status "$BASE/me" -H "Authorization: Bearer $TOKEN")
  [ "$code" = 200 ] && break
  sleep 0.5
done
[ "$code" = 200 ] && pass "GET /me with a fresh token → 200" || die "GET /me → $code"

EXPIRES=$(date -u -r "$EXP" +%Y-%m-%dT%H:%M:%S.000Z 2>/dev/null || date -u -d "@$EXP" +%Y-%m-%dT%H:%M:%S.000Z)
NOW=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)
ENVELOPE=$(jq -cn --arg m "$(uuid)" --arg c "$(uuid)" --arg t "$NOW" --arg j "$JTI" --arg u "$SUB" --arg e "$EXPIRES" \
  '{messageId:$m, correlationId:$c, type:"TokenRevoked", version:1, occurredAt:$t, payload:{jti:$j, userId:$u, expiresAt:$e, reason:"LOGOUT"}}')
TOPIC_ARN="arn:aws:sns:${AWS_REGION:-us-east-1}:000000000000:ticketpro-auth"
# Same attributes as the outbox publisher: subscriptions filter on `type` (infra/terraform/modules/messaging).
awsl sns publish --topic-arn "$TOPIC_ARN" --message "$ENVELOPE" \
  --message-attributes '{"type":{"DataType":"String","StringValue":"TokenRevoked"},"version":{"DataType":"String","StringValue":"1"}}' >/dev/null || die "sns publish failed"
pass "TokenRevoked published for jti $JTI"

code=""
for _ in $(seq 1 30); do
  code=$(status "$BASE/me" -H "Authorization: Bearer $TOKEN")
  [ "$code" = 401 ] && break
  sleep 0.5
done
[ "$code" = 401 ] && pass "GET /me with the revoked token → 401" || fail "revoked token still accepted ($code)"

limited=""
for _ in $(seq 1 15); do
  headers=$(curl -s -D - -o /dev/null -X POST "$BASE/auth/login" -H 'content-type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"wrong password\"}")
  if echo "$headers" | head -1 | grep -q " 429"; then
    limited=$(echo "$headers" | grep -i '^retry-after:' | tr -d '\r' | awk '{print $2}')
    break
  fi
done
[ -n "$limited" ] && pass "repeated logins → 429 (Retry-After: $limited)" || fail "no 429 after 15 logins"

# Leave the login bucket full again so scripts run right after (happy-path) are not throttled.
sleep "$(awk -v b="${AUTH_LOGIN_BURST:-5}" -v r="${AUTH_LOGIN_RPS:-1}" 'BEGIN { print int(b / r) + 1 }')"

echo "gateway-check: $failures failed"
[ "$failures" -eq 0 ]
