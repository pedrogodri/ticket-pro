# gateway

Ponto de entrada único do Ticket Pro, em Go. É o único serviço que publica porta (`8080`).

**Dono:** Pedro.

**Estado:** completo segundo a RFC 0002:
- roteamento por prefixo (`GATEWAY_ROUTES`);
- validação do JWT RS256 pelo JWKS do auth;
- troca dos headers de identidade;
- limite de corpo (413) e timeout de upstream (504);
- blacklist de `jti` no Redis próprio, alimentada por `TokenRevoked` (401);
- rate limit por IP/usuário (429 + `Retry-After`).

## Responsabilidades

[RFC 0002](../../docs/rfc/0002-authentication-and-authorization.md) e [ADR-0002](../../docs/adr/0002-gateway-validates-jwt-locally-via-jwks.md):

- roteamento por prefixo para auth, user, order e inventory;
- validação local do JWT (RS256) com o JWKS do auth em cache;
- remover do cliente os headers `X-User-Id`/`X-Correlation-Id` e injetar os valores confiáveis;
- rate limit por IP/usuário e limite de tamanho do corpo;
- blacklist de `jti` no Redis próprio, alimentada por `TokenRevoked`.

## API

| Endpoint | Fase | Resposta |
|---|---|---|
| `GET /health/live` | F0 ✅ | `200 {"status":"up"}` |
| `GET /health/ready` | ✅ | `200 {"status":"up","checks":{"redis":"up","broker":"up"}}`; Redis ou broker fora → `degraded`, ainda 200 (fail-open, ADR-0014) |
| `/auth/*`, `/me*`, `/orders*`, `/events*` | ✅ | proxy para o serviço dono |

### Rate limit

| Rota | Chave | Variáveis (padrão) |
|---|---|---|
| `POST /auth/login` | IP | `AUTH_LOGIN_RPS`=1, `AUTH_LOGIN_BURST`=5 |
| demais rotas públicas | IP | `RATE_LIMIT_PUBLIC_RPS`=5, `RATE_LIMIT_PUBLIC_BURST`=10 |
| `POST /orders` | usuário | `ORDERS_RPS`=2, `ORDERS_BURST`=5 |
| demais rotas autenticadas | usuário | `RATE_LIMIT_RPS`=10, `RATE_LIMIT_BURST`=20 |

O IP é o do peer TCP. `X-Forwarded-For` é ignorado, porque o gateway é a borda e esse header viria do próprio cliente.

## Eventos

| Publica | Consome (fila `ticketpro-gateway`) |
|---|---|
| — | `TokenRevoked` |

## Dados

Não tem Postgres. Usa o Redis próprio `gateway-redis` (rede interna `ticketpro-gateway-data`, sem persistência) para:
- a blacklist de `jti`: `revoked:{jti}`, com TTL até o `expiresAt` do token;
- os buckets do rate limit: `ratelimit:{chave}`.

Como `SET` com o mesmo TTL é idempotente, o consumidor não precisa de tabela de mensagens processadas. Se o Redis cair, a revogação e o rate limit são fail-open, com log `revocation_check_unavailable` / `rate_limit_unavailable`.

## Por onde começar

1. Ler a seção *Primeiros passos* do [README da raiz](../../README.md).
2. Ler:
   - [RFC 0002](../../docs/rfc/0002-authentication-and-authorization.md) §5.5 (validação no gateway) e §5.8 (OWASP);
   - [padrões](../../docs/padroes.md) §4 (HTTP e formato de erro).
3. Plano: [gateway completo](../../docs/superpowers/plans/2026-11-15-gateway-complete.md). Checagem ao vivo: `scripts/gateway-check.sh`.

## Variáveis de ambiente

| Variável | Obrigatória | Exemplo |
|---|---|---|
| `SERVICE_NAME` | sim | `gateway` |
| `PORT` | sim | `8080` |
| `LOG_LEVEL` | sim | `debug` \| `info` \| `warn` \| `error` |
| `GATEWAY_ROUTES` | sim | `auth=http://auth-service:8080,me=...` |
| `JWKS_URL` | sim | `http://auth-service:8080/.well-known/jwks.json` |
| `JWT_ISSUER`, `JWT_AUDIENCE`, `JWKS_CACHE_TTL_S`, `JWT_CLOCK_SKEW_S` | não | `ticketpro-auth`, `ticketpro-api`, 300, 30 |
| `GATEWAY_MAX_BODY_BYTES`, `GATEWAY_UPSTREAM_TIMEOUT_MS` | não | 65536, 5000 |
| `REDIS_URL` | sim | `redis://gateway-redis:6379/0` |
| `REDIS_TIMEOUT_MS` | não | 200 (passou disso, fail-open) |
| `QUEUE_URL`, `CONTRACTS_DIR` | sim | fila `ticketpro-gateway`, `/contracts` |
| `SQS_WAIT_TIME_S`, `HTTP_CLIENT_TIMEOUT_MS` | não | 10, 3000 |
| `RATE_LIMIT_*`, `AUTH_LOGIN_*`, `ORDERS_*` | não | ver *Rate limit* |

`internal/platform/` e `contracts/` são cópias geradas por `scripts/sync-shared.sh`: não edite.

## Comandos

```bash
make test        # go test
make lint        # gofmt + golangci-lint (container)
make typecheck   # go vet
make run         # sobe só este serviço (porta 8080)
```

Sem Go instalado, os alvos rodam sozinhos no container `golang:1.25`. Para forçar: `make test TOOLCHAIN=docker`. Também há um Dev Container em [`.devcontainer/`](.devcontainer/devcontainer.json).

## Tracing

OpenTelemetry ([ADR-0012](../../docs/adr/0012-opentelemetry-collector-jaeger.md)). `OTEL_EXPORTER_OTLP_ENDPOINT` (padrão `otel-collector:4317`) envia os spans ao collector, e de lá para o Jaeger (`http://localhost:16686`). Sem a variável, nada é exportado. `OTEL_SERVICE_NAME` é opcional; o padrão é `SERVICE_NAME`.
- **HTTP:** um span por requisição (`otelhttp`), exceto `/health/*`.
- **Upstreams:** span cliente por chamada, cujo `traceparent` substitui o que o cliente mandou.
- **Mensagens:** span `consumer` por mensagem, no trace do producer e com link para ele. Span `producer` por publicação do outbox, que propaga o `traceparent`.
