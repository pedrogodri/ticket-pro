#!/usr/bin/env bash
# Waits until every running container of the compose project that defines a healthcheck
# reports "healthy". Usage: scripts/wait-healthy.sh [timeout_seconds=180]
set -euo pipefail
cd "$(dirname "$0")/.."
timeout="${1:-180}"
deadline=$(( $(date +%s) + timeout ))

while :; do
  pending=()
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id")
    name=$(docker inspect -f '{{.Name}}' "$id")
    case "$status" in
      healthy|none) ;;
      unhealthy) echo "unhealthy: ${name#/}"; docker logs --tail 30 "$id" >&2; exit 1 ;;
      *) pending+=("${name#/}") ;;
    esac
  done < <(docker compose ps -q)
  [ "${#pending[@]}" -eq 0 ] && { echo "all containers healthy"; exit 0; }
  [ "$(date +%s)" -ge "$deadline" ] && { echo "timeout waiting for: ${pending[*]}"; exit 1; }
  sleep 2
done
