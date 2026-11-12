#!/usr/bin/env bash
# Re-running infra-init must be safe (it runs again on every `docker compose up`):
#   1. a message waiting in a queue survives `docker compose up -d`;
#   2. after a MiniStack restart (SNS/SQS lost, S3/Secrets kept), re-running infra-init
#      recreates what is missing and verify-infra passes again.
# Usage: scripts/check-infra-rerun.sh
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh
load_env

marker="rerun-$(date +%s)"
awsl sqs send-message --queue-url "$NOTIFICATION_SERVICE_QUEUE_URL" --message-body "$marker" >/dev/null

wait_infra_init() { # block until infra-init has run to completion after `since` (epoch seconds)
  local since="$1" id finished
  id="$(docker compose ps -aq infra-init)"
  for _ in $(seq 1 180); do
    if [ "$(docker inspect -f '{{.State.Running}}' "$id")" = "false" ]; then
      finished="$(docker inspect -f '{{.State.FinishedAt}}' "$id" | cut -d. -f1)"
      [ "$(date -j -u -f '%Y-%m-%dT%H:%M:%S' "$finished" +%s 2>/dev/null || date -u -d "$finished" +%s)" -ge "$since" ] && return 0
    fi
    sleep 1
  done
  echo "timeout waiting for infra-init"; return 1
}

since=$(date +%s)
docker compose up -d >/dev/null 2>&1
wait_infra_init "$since"

if awsl sqs receive-message --queue-url "$NOTIFICATION_SERVICE_QUEUE_URL" --wait-time-seconds 2 \
  | grep -q "$marker"; then
  echo "PASS  queued message survived docker compose up"
else
  echo "FAIL  queued message lost after docker compose up (infra-init wiped MiniStack)"; exit 1
fi

docker compose restart ministack >/dev/null 2>&1
docker compose up -d --force-recreate infra-init >/dev/null 2>&1
docker compose wait infra-init >/dev/null
scripts/verify-infra.sh >/dev/null && echo "PASS  infra recreated after MiniStack restart" \
  || { echo "FAIL  verify-infra after MiniStack restart"; exit 1; }
