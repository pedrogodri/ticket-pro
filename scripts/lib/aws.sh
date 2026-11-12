#!/usr/bin/env bash
# Shared helpers for scripts that talk to MiniStack. Source it: `. scripts/lib/aws.sh`.
# awsl runs the AWS CLI in a container attached to the backend network, so it reaches
# ministack:4566 exactly like the services do. Values come from the environment (.env).

AWS_CLI_IMAGE="${AWS_CLI_IMAGE:-amazon/aws-cli:2.39.7}"
INGRESSOJA_NETWORK="${INGRESSOJA_NETWORK:-ingressoja-backend}"

awsl() {
  docker run --rm \
    --network "$INGRESSOJA_NETWORK" \
    -e AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}" \
    -e AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}" \
    -e AWS_REGION="${AWS_REGION:-us-east-1}" \
    -e AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://ministack:4566}" \
    -e AWS_PAGER="" \
    "$AWS_CLI_IMAGE" --output json "$@"
}

# load_env: export variables from the repo .env (falls back to .env.example).
load_env() {
  local root file
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  file="$root/.env"
  [ -f "$file" ] || file="$root/.env.example"
  set -a
  # shellcheck disable=SC1090
  . "$file"
  set +a
}
