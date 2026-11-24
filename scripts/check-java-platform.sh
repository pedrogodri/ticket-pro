#!/usr/bin/env bash
# Guards against drift of the Java platform/ copies (ADR-0022, RFC 0004 §5.10): auth, order and
# inventory each own a copy under br.furb.ticketpro.<svc>.platform. A class present in two or more
# trees must be identical once the package and the "<svc>-service" literal are normalized.
#   - AppConfig* and ApiExceptionHandler are adapted per service: skipped.
#   - A class in a single tree is new platform code (born in order/inventory, ADR-0022): noted.
#   - A service without platform/ yet (no source) is skipped.
# A platform fix goes into every tree in the same PR.
# Usage: scripts/check-java-platform.sh [--root DIR]
set -uo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
if [ "${1:-}" = "--root" ]; then
  root="${2:?--root needs a directory}"
fi

failures=0
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
ok() { echo "ok    $1"; }
tree() { echo "$root/services/$1-service/src/main/java/br/furb/ticketpro/$1/platform"; }
norm() { sed -E 's/\.(auth|order|inventory)\./.<svc>./g; s/"(auth|order|inventory)-service"/"<svc>-service"/g' "$1"; }

present=()
for svc in auth order inventory; do
  if [ -d "$(tree "$svc")" ]; then
    present+=("$svc")
  else
    ok "$svc-service has no platform/ (service has no code yet)"
  fi
done
if [ "${#present[@]}" -lt 2 ]; then
  echo "check-java-platform: ${#present[@]} platform tree(s), nothing to compare"
  exit 0
fi

files="$(for svc in "${present[@]}"; do (cd "$(tree "$svc")" && find . -name '*.java' | sed 's|^\./||'); done | sort -u)"
for f in $files; do
  case "$f" in
    config/AppConfig*.java | web/ApiExceptionHandler.java)
      echo "skip  $f (adapted per service)"
      continue
      ;;
  esac
  holders=()
  for svc in "${present[@]}"; do
    [ -f "$(tree "$svc")/$f" ] && holders+=("$svc")
  done
  if [ "${#holders[@]}" -eq 1 ]; then
    echo "note  $f only in ${holders[0]}"
    continue
  fi
  ref="${holders[0]}"
  drift=0
  for svc in "${holders[@]:1}"; do
    if ! diff -q <(norm "$(tree "$ref")/$f") <(norm "$(tree "$svc")/$f") >/dev/null; then
      fail "$f: $svc differs from $ref"
      diff -u <(norm "$(tree "$ref")/$f") <(norm "$(tree "$svc")/$f") | tail -n +3 | head -20 | sed 's/^/      /'
      drift=1
    fi
  done
  [ "$drift" -eq 0 ] && ok "$f (${holders[*]})"
done

echo "check-java-platform: $failures failed"
[ "$failures" -eq 0 ]
