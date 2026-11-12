#!/usr/bin/env bash
# Only the gateway (and the Jaeger UI, an operations tool) may publish host ports
# (ADR-0002, ADR-0004, docs/padroes.md §9). Exit 0 if that holds for the whole compose project.
set -euo pipefail
cd "$(dirname "$0")/.."

allowed="gateway jaeger"
published="$(docker compose config --format json | jq -r '.services | to_entries[] | select((.value.ports // []) | length > 0) | .key' | sort)"

status=0
for svc in $published; do
  case " $allowed " in
    *" $svc "*) echo "ok    $svc publishes ports (allowed)" ;;
    *) echo "FAIL  $svc publishes ports"; status=1 ;;
  esac
done
[ "$status" -eq 0 ] && echo "port exposure OK"
exit "$status"
