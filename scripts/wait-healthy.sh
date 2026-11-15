#!/usr/bin/env bash
# Waits until the compose project is ready:
#   - every service declared in the compose config has a container;
#   - every long-running container is running, and healthy when it defines a healthcheck;
#   - every one-shot job has exited with code 0: infra-init plus every service named *-migrate
#     (override the list with ONE_SHOT_SERVICES).
# A stopped or crashed long-running service fails the check (it is not silently skipped).
# Usage: scripts/wait-healthy.sh [timeout_seconds=180]
set -euo pipefail
cd "$(dirname "$0")/.."
timeout="${1:-180}"
deadline=$(( $(date +%s) + timeout ))
migrate_jobs="$(docker compose config --services | grep -E -- "-migrate\$" | tr "\n" " " || true)"
ONE_SHOT_SERVICES="${ONE_SHOT_SERVICES:-infra-init $migrate_jobs}"

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

  for svc in $(docker compose config --services); do
    case " $ONE_SHOT_SERVICES " in *" $svc "*) continue ;; esac
    ids="$(docker compose ps -aq "$svc" 2>/dev/null || true)"
    [ -n "$ids" ] || { pending+=("$svc (no container)"); continue; }
    for id in $ids; do
      state=$(docker inspect -f '{{.State.Status}}' "$id")
      case "$state" in
        running) ;;
        created|restarting) pending+=("$svc ($state)"); continue ;;
        *) echo "service not running: $svc ($state)"; docker logs --tail 30 "$id" >&2; exit 1 ;;
      esac
      status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id")
      case "$status" in
        healthy|none) ;;
        unhealthy) echo "unhealthy: $svc"; docker logs --tail 30 "$id" >&2; exit 1 ;;
        *) pending+=("$svc ($status)") ;;
      esac
    done
  done

  [ "${#pending[@]}" -eq 0 ] && { echo "all containers healthy"; exit 0; }
  [ "$(date +%s)" -ge "$deadline" ] && { echo "timeout waiting for: ${pending[*]}"; exit 1; }
  sleep 2
done
