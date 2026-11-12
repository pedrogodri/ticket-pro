#!/usr/bin/env bash
# Asserts the per-service database platform (docs/padroes.md §6, ADR-0020) on the running stack:
#   1. roles <db>_app (no BYPASSRLS) and <db>_worker (BYPASSRLS) exist;
#   2. processed_messages exists, and outbox too when the service publishes;
#   3. outbox/processed_messages columns are identical across every database that has them;
#   4. the service readiness reports checks.db == "up".
# Exit 0 only if every check passes. Usage: scripts/verify-platform.sh
set -uo pipefail
cd "$(dirname "$0")/.."

BUSYBOX_IMAGE="busybox:1.37.0"
NETWORK="${TICKETPRO_NETWORK:-ticketpro-backend}"
# db:service:publishes (bash 3.2 compatible: no associative arrays)
DATABASES="
auth:auth-service:yes
users:user-service:yes
orders:order-service:yes
inventory:inventory-service:yes
payments:payment-service:yes
notifications:notification-service:no
"

checks=0
failures=0
pass() { checks=$((checks + 1)); echo "ok    $1"; }
fail() { checks=$((checks + 1)); failures=$((failures + 1)); echo "FAIL  $1"; }

psql_in() { # db, sql
  docker compose exec -T "$1-db" psql -U "$1_owner" -d "$1" -Atq -v ON_ERROR_STOP=1 -c "$2"
}

COLUMNS_SQL="SELECT table_name, column_name, data_type, is_nullable, coalesce(column_default,'') FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '%s' ORDER BY 1, 2"

workdir="$(mktemp -d "${TMPDIR:-/tmp}/verify-platform.XXXXXX")"
trap 'rm -rf "${workdir:?}"' EXIT

found=0
for entry in $DATABASES; do
  db="${entry%%:*}"; rest="${entry#*:}"; svc="${rest%%:*}"; publishes="${rest#*:}"
  [ -n "$(docker compose ps -q "$db-db" 2>/dev/null)" ] || continue
  found=$((found + 1))

  app_bypass="$(psql_in "$db" "SELECT rolbypassrls FROM pg_roles WHERE rolname = '${db}_app'" 2>/dev/null)"
  worker_bypass="$(psql_in "$db" "SELECT rolbypassrls FROM pg_roles WHERE rolname = '${db}_worker'" 2>/dev/null)"
  [ "$app_bypass" = "f" ] && pass "$db: role ${db}_app without BYPASSRLS" || fail "$db: role ${db}_app missing or with BYPASSRLS ($app_bypass)"
  [ "$worker_bypass" = "t" ] && pass "$db: role ${db}_worker with BYPASSRLS" || fail "$db: role ${db}_worker missing or without BYPASSRLS ($worker_bypass)"

  tables="processed_messages"
  [ "$publishes" = "yes" ] && tables="outbox processed_messages"
  for t in $tables; do
    # shellcheck disable=SC2059
    psql_in "$db" "$(printf "$COLUMNS_SQL" "$t")" > "$workdir/$db.$t" 2>/dev/null
    if [ -s "$workdir/$db.$t" ]; then pass "$db: table $t exists"; else fail "$db: table $t missing"; rm -f "$workdir/$db.$t"; fi
  done

  ready="$(docker run --rm --network "$NETWORK" "$BUSYBOX_IMAGE" wget -qO- -T 5 "http://$svc:8080/health/ready" 2>/dev/null)"
  if echo "$ready" | jq -e '.checks.db == "up"' >/dev/null 2>&1; then
    pass "$svc: /health/ready reports db up"
  else
    fail "$svc: /health/ready does not report db up ($ready)"
  fi
done

if [ "$found" -eq 0 ]; then
  echo "no databases found (expected containers named <db>-db in the ticketpro project)"
  exit 1
fi

# Same DDL everywhere: compare every copy of a table against the first one found.
for t in outbox processed_messages; do
  ref=""
  for f in "$workdir"/*."$t"; do
    [ -e "$f" ] || continue
    if [ -z "$ref" ]; then ref="$f"; continue; fi
    if cmp -s "$ref" "$f"; then
      pass "$t: $(basename "$f" ".$t") matches $(basename "$ref" ".$t")"
    else
      fail "$t: $(basename "$f" ".$t") differs from $(basename "$ref" ".$t")"
      diff "$ref" "$f" | sed 's/^/      /'
    fi
  done
done

echo "verify-platform: $checks checks, $failures failed"
[ "$failures" -eq 0 ]
