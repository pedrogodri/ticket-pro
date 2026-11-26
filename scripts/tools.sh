#!/usr/bin/env bash
# Visual tools for the demo (docs/apresentacao/guia-demo.md §5.2). They run OUTSIDE the compose project:
# they are not part of the system, scripts/check-ports.sh keeps checking only the services, and the ports
# are bound to 127.0.0.1. pgAdmin joins every <svc>-data network, so it is the one place that sees all the
# databases: an operations tool, like Jaeger, never something a service does (ADR-0004).
#   pgAdmin       http://localhost:5050  every service database, no login (owner credentials read from
#                                        services/*/db.env.example, development only)
#   RedisInsight  http://localhost:5540  gateway-redis (jti blacklist and rate limit buckets, ADR-0014)
#   SQS Admin     http://localhost:3999  MiniStack queues and DLQs (third-party image, pinned to a release).
#                                        DLQs ONLY. The selected queue is polled every ~3 s and every poll is a
#                                        receive: on a live queue (maxReceiveCount=3) a waiting message lands
#                                        in its DLQ in ~15 s (tested 25/11), and the saga stalls. Never select
#                                        a live queue, never use PURGE/DELETE; for live queues use queues.sh.
# Usage: scripts/tools.sh up | down | urls
# Run `scripts/tools.sh down` BEFORE `docker compose down`: a container attached to the networks keeps
# compose from removing them.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
. scripts/lib/aws.sh; load_env

PGADMIN_IMAGE="${PGADMIN_IMAGE:-dpage/pgadmin4:9.19}"
REDISINSIGHT_IMAGE="${REDISINSIGHT_IMAGE:-redis/redisinsight:3.10}"
PGADMIN_PORT="${PGADMIN_PORT:-5050}"
REDISINSIGHT_PORT="${REDISINSIGHT_PORT:-5540}"
SQSADMIN_IMAGE="${SQSADMIN_IMAGE:-pacovk/sqs-admin:0.9.1}"
SQSADMIN_PORT="${SQSADMIN_PORT:-3999}"
PGADMIN=ticketpro-tools-pgadmin
REDISINSIGHT=ticketpro-tools-redisinsight
SQSADMIN=ticketpro-tools-sqsadmin
DB_SERVICES="order-service inventory-service payment-service notification-service user-service auth-service"

# The tools start on Docker's default bridge (the <svc>-data networks are internal: no published ports
# there) and are then connected to the internal networks they need to reach.

# env_value FILE KEY → the value of KEY in a dotenv file
env_value() { sed -n "s/^$2=//p" "$1" | head -1; }

pgadmin_up() {
  local dir svc f user pass db n=0 servers=""
  dir="$(mktemp -d)"
  : > "$dir/pgpass"
  for svc in $DB_SERVICES; do
    f="services/$svc/db.env.example"
    [ -f "$f" ] || continue
    user=$(env_value "$f" POSTGRES_USER); pass=$(env_value "$f" POSTGRES_PASSWORD); db=$(env_value "$f" POSTGRES_DB)
    n=$((n + 1))
    printf '%s-db:5432:%s:%s:%s\n' "$db" "$db" "$user" "$pass" >> "$dir/pgpass"
    servers="$servers${servers:+,}\"$n\":{\"Name\":\"$db ($svc)\",\"Group\":\"Ticket Pro\",\"Host\":\"$db-db\",\"Port\":5432,\"MaintenanceDB\":\"$db\",\"Username\":\"$user\",\"PassFile\":\"/var/lib/pgadmin/pgpass\",\"SSLMode\":\"disable\"}"
  done
  printf '{"Servers":{%s}}\n' "$servers" > "$dir/servers.json"
  chmod 644 "$dir/pgpass" "$dir/servers.json"
  docker run -d --name "$PGADMIN" -p "127.0.0.1:$PGADMIN_PORT:80" \
    -e PGADMIN_DEFAULT_EMAIL=admin@ticketpro.test -e PGADMIN_DEFAULT_PASSWORD=admin \
    -e PGADMIN_CONFIG_ALLOW_SPECIAL_EMAIL_DOMAINS="['test']" \
    -e PGADMIN_CONFIG_SERVER_MODE=False -e PGADMIN_CONFIG_MASTER_PASSWORD_REQUIRED=False \
    -e PGADMIN_SERVER_JSON_FILE=/in/servers.json -e PGPASSFILE=/var/lib/pgadmin/pgpass \
    -v "$dir:/in:ro" \
    --entrypoint /bin/sh "$PGADMIN_IMAGE" \
    -c 'cp /in/pgpass /var/lib/pgadmin/pgpass && chmod 600 /var/lib/pgadmin/pgpass && exec /entrypoint.sh' >/dev/null
  for svc in $DB_SERVICES; do docker network connect "ticketpro-$svc-data" "$PGADMIN"; done
  echo "pgAdmin       http://localhost:$PGADMIN_PORT  ($n bancos, sem login)"
}

redisinsight_up() {
  docker run -d --name "$REDISINSIGHT" -p "127.0.0.1:$REDISINSIGHT_PORT:5540" \
    "$REDISINSIGHT_IMAGE" >/dev/null
  docker network connect ticketpro-gateway-data "$REDISINSIGHT"
  # Register gateway-redis through the RedisInsight API (its env-based presets are not picked up by 3.x)
  local api="http://localhost:$REDISINSIGHT_PORT/api/databases"
  for _ in $(seq 1 60); do curl -sf "$api" >/dev/null && break; sleep 1; done
  curl -sf "$api" | jq -e 'any(.[]; .host == "gateway-redis")' >/dev/null ||
    curl -sf -X POST "$api" -H 'Content-Type: application/json' \
      -d '{"name":"gateway-redis","host":"gateway-redis","port":6379}' >/dev/null
  echo "RedisInsight  http://localhost:$REDISINSIGHT_PORT  (gateway-redis)"
}

sqsadmin_up() {
  # ticketpro-backend is not internal, so the port can be published straight from it
  docker run -d --name "$SQSADMIN" -p "127.0.0.1:$SQSADMIN_PORT:3999" --network "$TICKETPRO_NETWORK" \
    -e SQS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://ministack:4566}" -e SQS_AWS_REGION="${AWS_REGION:-us-east-1}" \
    -e AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}" -e AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}" \
    "$SQSADMIN_IMAGE" >/dev/null
  echo "SQS Admin     http://localhost:$SQSADMIN_PORT  (SÓ DLQs: selecionar uma fila viva manda as mensagens dela para a DLQ)"
}

urls() {
  docker ps --filter "name=ticketpro-tools-" --format '{{.Names}}  {{.Status}}  {{.Ports}}'
  echo "Jaeger        http://localhost:16686"
}

case "${1:-}" in
  up)
    docker network inspect ticketpro-gateway-data >/dev/null 2>&1 || { echo "suba a stack antes: docker compose up -d" >&2; exit 1; }
    if docker inspect "$PGADMIN" >/dev/null 2>&1; then echo "pgAdmin já está no ar"; else pgadmin_up; fi
    if docker inspect "$REDISINSIGHT" >/dev/null 2>&1; then echo "RedisInsight já está no ar"; else redisinsight_up; fi
    if docker inspect "$SQSADMIN" >/dev/null 2>&1; then echo "SQS Admin já está no ar"; else sqsadmin_up; fi ;;
  down) docker rm -f "$PGADMIN" "$REDISINSIGHT" "$SQSADMIN" >/dev/null 2>&1; echo "ferramentas removidas" ;;
  urls) urls ;;
  *) echo "usage: $0 up | down | urls" >&2; exit 2 ;;
esac
