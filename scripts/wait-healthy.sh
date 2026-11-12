#!/usr/bin/env bash
# Waits until the compose project is ready:
#   - every running container that defines a healthcheck reports "healthy";
#   - every one-shot job (ONE_SHOT_SERVICES) has exited with code 0.
# Usage: scripts/wait-healthy.sh [timeout_seconds=180]
set -euo pipefail
cd "$(dirname "$0")/.."
timeout="${1:-180}"
deadline=$(( $(date +%s) + timeout ))
ONE_SHOT_SERVICES="${ONE_SHOT_SERVICES:-infra-init}"

while :; do
  pending=()

  for job in $ONE_SHOT_SERVICES; do
    id="$(docker compose ps -aq "$job" 2>/dev/null || true)"
    [ -n "$id" ] || continue
    state="$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$id")"
    case "$state" in
      "exited 0") ;;
      exited*) echo "one-shot job failed: $job ($state)"; docker logs --tail 30 "$id" >&2; exit 1 ;;
      *) pending+=("$job") ;;
    esac
  done

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
