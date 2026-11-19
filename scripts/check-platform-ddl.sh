#!/usr/bin/env bash
# Guards against drift of the platform DDL (docs/padroes.md §6.2, review Focus 5):
#   - every service's outbox / processed_messages migration is byte-identical to inventory-service's;
#   - the four test fixtures (one per platform implementation) are identical;
#   - the fixtures contain exactly those CREATE statements (comments ignored).
# Usage: scripts/check-platform-ddl.sh
set -uo pipefail
cd "$(dirname "$0")/.."

failures=0
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
ok() { echo "ok    $1"; }
body() { grep -v '^--' "$1" | sed '/^[[:space:]]*$/d'; } # SQL without comments or blank lines

ref_outbox=services/inventory-service/migrations/V0002__create_outbox.sql
ref_processed=services/inventory-service/migrations/V0003__create_processed_messages.sql
for f in services/*/migrations/V000[23]__create_outbox.sql; do
  cmp -s "$f" "$ref_outbox" && ok "$f" || fail "$f differs from $ref_outbox"
done
for f in services/*/migrations/V000[23]__create_processed_messages.sql; do
  cmp -s "$f" "$ref_processed" && ok "$f" || fail "$f differs from $ref_processed"
done

ref_fixture=shared/go/internal/testenv/platform.sql
for f in shared/dotnet/tests/TicketPro.Platform.Tests/TestEnv/platform.sql \
  services/auth-service/src/test/resources/platform.sql; do
  [ -f "$f" ] || { ok "$f absent (service has no code yet)"; continue; }
  cmp -s "$f" "$ref_fixture" && ok "$f" || fail "$f differs from $ref_fixture"
done
if body "$ref_fixture" | grep -qxF -f <( { body "$ref_outbox"; body "$ref_processed"; } ) &&
  diff <( { body "$ref_outbox"; body "$ref_processed"; } ) <(body "$ref_fixture" | sed '/^CREATE TABLE effects/,$d') >/dev/null; then
  ok "fixture matches the migrations"
else
  fail "fixture $ref_fixture does not match V0002 + V0003"
fi

echo "check-platform-ddl: $failures failed"
[ "$failures" -eq 0 ]
