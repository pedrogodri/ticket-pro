#!/usr/bin/env bash
# p22 (RFC 0004 §5.10): tests scripts/check-java-platform.sh against throwaway platform trees.
# Usage: scripts/tests/check-java-platform.test.sh
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../check-java-platform.sh"
fx="$(mktemp -d "${TMPDIR:-/tmp}/check-java-platform-test.XXXXXX")"
trap 'rm -rf "${fx:?}"' EXIT

failures=0
pass() { echo "ok    $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
run() { "$script" --root "$fx" >"$fx/out" 2>&1; }
expect_pass() { if run; then pass "$1"; else fail "$1"; sed 's/^/      /' "$fx/out"; fi; }
expect_fail() { # description, text the output must name
  if run; then fail "$1 (exit 0)"; elif grep -qF "$2" "$fx/out"; then pass "$1"; else fail "$1 (output lacks $2)"; fi
}

tree() { echo "$fx/services/$1-service/src/main/java/br/furb/ticketpro/$1/platform"; }
# file under platform/, service, body: writes one class into the service's tree
put() {
  local file="$1" svc="$2" body="$3" dir pkg
  dir="$(dirname "$file")"
  pkg="br.furb.ticketpro.$svc.platform${dir:+.${dir//\//.}}"
  [ "$dir" = "." ] && pkg="br.furb.ticketpro.$svc.platform"
  mkdir -p "$(tree "$svc")/$dir"
  printf 'package %s;\n\nimport br.furb.ticketpro.%s.platform.retry.Backoff;\n\n%s\n' "$pkg" "$svc" "$body" \
    >"$(tree "$svc")/$file"
}
reset() {
  rm -rf "$fx/services"
  for svc in auth order inventory; do
    put retry/Backoff.java "$svc" 'public final class Backoff {}'
    put logging/Formatter.java "$svc" "class Formatter { String name = \"$svc-service\"; }"
    put config/AppConfig.java "$svc" "public record AppConfig(String $svc) {}"
    put web/ApiExceptionHandler.java "$svc" "class ApiExceptionHandler { int $svc; }"
  done
}

reset
expect_pass "three equal trees (package and service name differ) pass"

reset
put retry/Backoff.java inventory 'public final class Backoff { int drift; }'
expect_fail "a divergent class fails and is named" "retry/Backoff.java: inventory differs from auth"

reset
put retry/Backoff.java order 'public final class Backoff { int drift; }'
expect_fail "the divergent service is named" "order differs from auth"

reset
put config/AppConfigConfiguration.java order 'class AppConfigConfiguration { int order; }'
put config/AppConfigConfiguration.java auth 'class AppConfigConfiguration { int auth; }'
expect_pass "AppConfig* and ApiExceptionHandler are exceptions"

reset
put jobs/PeriodicJob.java order 'public interface PeriodicJob {}'
expect_pass "a class only in one tree (new platform code) passes"

reset
rm -rf "$fx/services/auth-service"
put retry/Backoff.java inventory 'public final class Backoff { int drift; }'
expect_fail "a missing tree is skipped and the others are still compared" "inventory differs from order"

reset
rm -rf "$fx/services/auth-service" "$fx/services/inventory-service"
expect_pass "a single tree passes (nothing to compare)"

echo "check-java-platform tests: $failures failed"
[ "$failures" -eq 0 ]
