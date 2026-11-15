# Gateway completo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fechar o gateway segundo a RFC 0002: Redis próprio, blacklist de `jti` alimentada por `TokenRevoked` e rate limit por IP/usuário com 429.

**Architecture:** Um Redis só do gateway (rede `gateway-data`, interna). `internal/revocation` guarda `revoked:{jti}` com TTL e consome `TokenRevoked` da fila `ticketpro-gateway`. `internal/ratelimit` é um token bucket atômico (script Lua) por chave. O edge (`internal/proxy`) consulta os dois; ambos são fail-open (ADR-0014).

**Tech Stack:** Go 1.25, go-redis v9.22.0, miniredis v2.39.0 (testes), redis:8.2-alpine, shared/go vendorizado (envelope, retry, logging).

**Spec:** `docs/rfc/0002-authentication-and-authorization.md` §5.5, §5.8; `docs/adr/0014-token-revocation-via-event.md`.

## Global Constraints

- Só o gateway publica porta; o Redis não publica nada e fica numa rede interna `ticketpro-gateway-data`.
- Fail-open no Redis (revogação e rate limit), com log `revocation_check_unavailable` / `rate_limit_unavailable`.
- Erros em problem+json: `UNAUTHENTICATED` 401, `RATE_LIMITED` 429 + `Retry-After` (padrões §4).
- Toda taxa, TTL, URL e nome de fila vem de env. Dependências com ≥ 2 semanas; go fica em 1.25.
- `internal/platform/` é cópia gerada por `scripts/sync-shared.sh`: não editar.

## Review Focus

1. **Redis fora do ar** → requisições autenticadas passam (fail-open) e ficam logadas; nunca 500. Teste na Task 1 e na Task 3.
2. **`TokenRevoked` com `expiresAt` no passado** → nada gravado (o token já expirou), mensagem confirmada. Teste na Task 2.
3. **Mensagem inválida na fila** → não confirmada e vai para a DLQ pelo redrive, sem travar as outras. Teste na Task 2.
4. **Cliente forjando `X-Forwarded-For`** → a chave do rate limit por IP usa `RemoteAddr`, não o header. Teste na Task 3.
5. **Rota com limite próprio** (`POST /auth/login`, `POST /orders`) não consome o bucket geral do usuário, e usuários distintos não compartilham bucket. Teste na Task 3.

---

### Task 1: Redis do gateway e checagem de revogação

**Files:**
- Create: `services/gateway/internal/revocation/store.go`, `store_test.go`
- Modify: `services/gateway/internal/proxy/proxy.go`, `proxy_test.go`, `internal/app/config.go`, `config_test.go`, `compose.yaml`, `.env.example`, `go.mod`

**Interfaces:**
- Produces: `revocation.Store{Client redis.UniversalClient}`; `(s Store) Revoke(ctx, jti string, expiresAt time.Time, now time.Time) error`; `(s Store) IsRevoked(ctx, jti string) (bool, error)`. Proxy: `Config.Revocations RevocationChecker` com `IsRevoked(ctx, jti) (bool, error)`.
- Config: `REDIS_URL` (obrigatória), `REDIS_TIMEOUT_MS` (padrão 200).

- [ ] Testes de store (miniredis): `TestRevokeSetsKeyWithTTLUntilExpiry` (TTL = expiresAt−now), `TestRevokeIgnoresExpiredToken` (nenhuma chave), `TestIsRevoked`.
- [ ] Testes de proxy: `TestRevokedTokenIsRejected` (401 `UNAUTHENTICATED`, upstream não chamado), `TestRevocationStoreDownFailsOpen` (checker com erro → 200 encaminhado, log `revocation_check_unavailable`).
- [ ] Ver RED, implementar, ver GREEN. Compose: serviço `gateway-redis` (`redis:8.2-alpine`, `--save "" --appendonly no`, healthcheck `redis-cli ping`), rede `gateway-data` interna; gateway `depends_on` healthy.
- [ ] `make lint typecheck test`; commit `feat(gateway): reject revoked tokens from its own Redis`.

### Task 2: Consumidor de `TokenRevoked`

**Files:**
- Modify: `scripts/sync-shared.conf` (linha `go gateway github.com/ticketpro/gateway`), `services/gateway/internal/platform/**` (gerado), `cmd/gateway/main.go`, `internal/app/{config,health,server}.go`
- Create: `services/gateway/internal/revocation/consumer.go`, `consumer_test.go`

**Interfaces:**
- Produces: `revocation.Consumer` com `Process(ctx, msg sqstypes.Message) Outcome` e `Run(ctx) error`, `BrokerUp() bool`. Config: `QUEUE_URL`, `CONTRACTS_DIR`, `SQS_WAIT_TIME_S` (10), `HTTP_CLIENT_TIMEOUT_MS` (3000), `AWS_*`.
- Sem Postgres: a escrita (`SET revoked:{jti} EX ttl`) é idempotente por natureza, então não há tabela de processadas.

- [ ] Testes (miniredis + `Process` com SQS falso via interface `queue`): válido → chave gravada e `DeleteMessage`; repetido 5× → uma chave, 5 acks; `expiresAt` passado → ack sem chave; envelope inválido → sem ack, `ChangeMessageVisibility 0`, log `message_rejected`; Redis fora → sem ack, retry com backoff (`message_retry`).
- [ ] Readiness: `checks.redis` e `checks.broker`; `503` se algum estiver `down`.
- [ ] `make lint typecheck test`, `scripts/sync-shared.sh --check`; commit `feat(gateway): consume TokenRevoked into the revocation blacklist`.

### Task 3: Rate limit

**Files:**
- Create: `services/gateway/internal/ratelimit/limiter.go`, `limiter_test.go`
- Modify: `internal/proxy/proxy.go`, `proxy_test.go`, `internal/app/config.go`, `config_test.go`, `.env.example`

**Interfaces:**
- Produces: `ratelimit.Limiter{Client, Clock func() time.Time}`; `(l Limiter) Allow(ctx, key string, rule Rule) (Decision, error)`; `Rule{RPS float64; Burst int}`; `Decision{Allowed bool; RetryAfter time.Duration}`. Token bucket em Lua (`ratelimit:{key}` com tokens e ts, TTL = burst/rps).
- Proxy: `Config.Limiter`, `Config.Limits{Default, Public, Login, Orders Rule}`. Chave: `ip:<RemoteAddr host>` nas rotas públicas, `user:<sub>` nas demais; login usa `login:<ip>`, `POST /orders` usa `orders:<sub>`.
- Env: `RATE_LIMIT_RPS` 10, `RATE_LIMIT_BURST` 20, `RATE_LIMIT_PUBLIC_RPS` 5, `RATE_LIMIT_PUBLIC_BURST` 10, `AUTH_LOGIN_RPS` 1, `AUTH_LOGIN_BURST` 5, `ORDERS_RPS` 2, `ORDERS_BURST` 5.

- [ ] Testes do limiter: burst 3 → 3 permitidas, 4ª negada com `RetryAfter` > 0; após 1/rps segundos (relógio injetado) volta a permitir; chaves distintas são independentes.
- [ ] Testes do proxy: 429 `RATE_LIMITED` com `Retry-After` inteiro ≥ 1; `X-Forwarded-For` diferente não muda a chave; login tem bucket próprio; Redis fora → passa com log `rate_limit_unavailable`.
- [ ] `make lint typecheck test`; commit `feat(gateway): rate limit by IP and user with stricter login and order buckets`.

### Task 4: Documentação e checagem ao vivo

**Files:**
- Modify: `services/gateway/README.md` (estado, envs, dados), `docs/roadmap.md` só se marcar itens
- Create: `scripts/gateway-check.sh`

- [ ] `scripts/gateway-check.sh`: cadastra e loga; `GET /me` 200; publica `TokenRevoked` com o `jti` do token no tópico `ticketpro-auth` via `awsl sns publish` (envelope válido); `GET /me` vira 401 em até 10 s; 6 logins errados seguidos → algum 429 com `Retry-After`.
- [ ] Do zero (`docker compose down -v && up --build`): `wait-healthy`, `gateway-check.sh`, `happy-path.sh`, `check-ports.sh`, `check-autonomy.sh services/gateway`, `verify-infra.sh`.
- [ ] Commit `docs(gateway): document Redis, revocation and rate limit settings`.
