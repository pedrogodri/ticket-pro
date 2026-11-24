# RFC 0010 — Design do gateway em Go

- **Status:** 📝 Draft (serviço praticamente completo; este RFC fixa o estado verificado, uma correção obrigatória e os riscos de borda — tarefas no [plano](../superpowers/plans/2026-11-19-gateway.md))
- **Scope:** O `gateway` (Go): roteamento por prefixo, validação local do JWT RS256 via JWKS, troca dos headers de identidade, rate limit, limites de corpo e timeout, blacklist de `jti` alimentada por `TokenRevoked`, health. Implementa o RFC 0002 §5.5 e o ADR-0002/0014; **não os redefine**. **Sem mudança de contrato e sem banco.** Cobre uma divergência com o RFC 0002 (a rota `refresh` não é pública) e a cobertura de testes de borda que o RFC exige.
- **Related:** [RFC 0002 §5.5, §5.8](0002-authentication-and-authorization.md), [ADR-0002](../adr/0002-gateway-validates-jwt-locally-via-jwks.md), [ADR-0014](../adr/0014-token-revocation-via-event.md), [RFC 0003 §5.1](0003-failure-handling-strategy.md), [arquitetura §2, §3](../arquitetura.md), [`services/gateway/`](../../services/gateway/README.md), [`contracts/events/TokenRevoked`](../../contracts/events/TokenRevoked/v1.schema.json), [`scripts/gateway-check.sh`](../../scripts/gateway-check.sh), [RFC 0007](0007-design-do-auth-service-em-java.md)

---

## 1. Summary

O gateway é a única porta publicada. Para cada requisição: gera `X-Correlation-Id`, remove `X-User-Id`/`X-User-Email`/`X-Internal-*`, aplica rate limit, valida o JWT localmente (JWKS em cache, `kid` desconhecido → refetch limitado), checa `revoked:{jti}` no Redis próprio (fail-open) e injeta a identidade. Está completo, salvo `POST /auth/refresh`, que o código trata como rota **protegida**, o que torna o refresh inútil.

## 2. Motivation

É a fronteira de segurança do sistema (RFC 0002 §9: todo serviço confia em `X-User-Id`) e carrega dois itens de apresentação: JWT local com JWKS e revogação por evento. Duas lacunas aparecem só na leitura cruzada: a rota de refresh e o fato de a revogação nunca ter sido exercitada com um `TokenRevoked` produzido pelo auth real.

## 3. Current state

Verificado em 19/11 em `services/gateway`:

| Item | Estado | Evidência |
|---|---|---|
| Roteamento pelo primeiro segmento do caminho (`GATEWAY_ROUTES`: `auth`, `me`, `orders`, `events`); `/.well-known` não é roteado | ✅ | `proxy.go`, `.env.example` |
| JWT: `alg` RS256, `kid` em cache, refetch ≤ 1/`RefetchMinInterval`, `iss`, `aud`, `exp`/`nbf` com folga | ✅ | `jwtauth`, 4 testes |
| Remoção/injeção de `X-User-Id`, `X-User-Email`, `X-Correlation-Id`; `X-Client-Request-Id` | ✅ | `TestClientIdentityHeadersAreStripped` |
| Rate limit (token bucket no Redis): login por IP, públicas por IP, `POST /orders` por usuário, demais por usuário, tokens inválidos por IP; IP = peer TCP | ✅ | `ratelimit`, `proxy_test` |
| Blacklist: `TokenRevoked` → `revoked:{jti}` com TTL até `expiresAt`; fail-open com cooldown | ✅ | `revocation`, `TestRevocationStoreDownFailsOpen` |
| Limite de corpo (413), timeout upstream (504; o código também tem 502), `Cache-Control: no-store` | ✅ | `TestEdgeProtections`, `TestUpstreamDownIs502` |
| Tracing HTTP e de mensagens; `traceparent` do upstream substitui o do cliente | ✅ | `tracing_test` |
| **Rotas públicas:** `{POST /auth/register, POST /auth/login}` | ⚠️ | `cmd/gateway/main.go:85`; o RFC 0002 §5.5 lista também `refresh` |
| CORS (`CORS_ALLOWED_ORIGINS`) | ❌ por escolha | não implementado; sem frontend, o RFC diz "vazio = desabilitado" |
| Biblioteca JWT (RFC 0002 Decisão 8 `OPEN`) | resolvida: `golang-jwt/jwt/v5` | `go.mod` |
| README | link do ADR-0012 aponta para `0012-opentelemetry-collector-jaeger.md`, que não existe (o arquivo é `0012-opentelemetry-tracing.md`) | link quebrado |

## 4. Goals / Non-goals

**Goals**

- Refresh utilizável sem Authorization válido.
- Garantir por teste que nenhuma rota além das públicas passa sem token e que `/.well-known` e `/health` não são alcançáveis de fora pelos prefixos roteados.
- Revogação ponta a ponta com o evento real do auth.

**Non-goals**

- mTLS, CORS, WAF, rate limit distribuído com garantias fortes (aceita-se aproximação).
- Introspecção no auth (anularia o ADR-0002).

## 5. Design

### 5.1 Tabela de rotas

| Rota | Pública | Limite | Upstream |
|---|---|---|---|
| `POST /auth/register`, `POST /auth/login` | sim | IP (`login`: 1 rps, burst 5) | auth |
| `POST /auth/refresh` | **sim (a corrigir)** | IP, regra pública | auth |
| `POST /auth/logout` | não | usuário | auth |
| `GET /me`, `GET /me/tickets` | não | usuário | user |
| `POST /orders`, `GET /orders`, `GET /orders/{id}` | não | `POST`: usuário (2 rps, burst 5) | order |
| `GET /events`, `GET /events/{id}` | não | usuário | inventory |
| qualquer outro | 404 | — | — |

A tabela mora em `GATEWAY_ROUTES` + o mapa `Public` do `main.go`; a arquitetura §3 é a lista humana (API9, RFC 0002 §5.8). A correção é incluir `"POST /auth/refresh": true` em `Public` e um teste que prove: `refresh` sem Bearer chega ao upstream; `logout` sem Bearer dá 401.

### 5.2 Revogação ponta a ponta

O auth publica `TokenRevoked` no logout (RFC 0007). O gateway grava `revoked:{jti}` com TTL = `expiresAt − now`; `expiresAt` no passado é ack sem efeito. Idempotente por `SET` com TTL, sem tabela de mensagens. Um token revogado cai em 401 com `WWW-Authenticate: Bearer error="invalid_token"` e o motivo só no log (`token_rejected reason=token revoked`).

### 5.3 Rate limit versus os cenários

`ORDERS_RPS=2`, `ORDERS_BURST=5` por usuário: o F5 usa 20 usuários × 5 pedidos (5 = burst) e fica dentro do limite. Qualquer script novo que dispare `POST /orders` repetido por um usuário deve usar N usuários (RFC 0002 Decisão 9, resolvida na prática pelo `f5-race.sh`).

### 5.4 Falhas

| Falha | Comportamento |
|---|---|
| Redis fora | revogação e rate limit fail-open, log `revocation_check_unavailable` / `rate_limit_unavailable`, cooldown de `REDIS_COOLDOWN_MS` |
| Auth fora no boot | JWKS carregado sob demanda; sem chave, toda rota protegida responde 401 e o log diz por quê. Verificar se deveria ser 503 (Questão 1) |
| Broker fora | consumidor do `TokenRevoked` em backoff; revogações novas atrasam; `/health/ready` `degraded`, 200 |
| Upstream fora | 502 (conexão) ou 504 (tempo) com `problem+json` |

### 5.5 Observabilidade e configuração

Logs JSON com `correlationId`, motivo da rejeição, rota. Todas as chaves em `.env.example` (README tem a tabela). Sem segredo.

## 6. Tests

Existentes: validação de token (tamper, skew, kid), identidade, 401, borda, revogação (fail-open), rate limit por chave, fila (`TokenRevoked` duplicado ×N). A acrescentar: refresh público, `alg=none`/HS256 rejeitados (confirmar que está coberto em `RejectsTamperedTokens`), `/.well-known/jwks.json` e `/health/*` não encaminhados por prefixo, e o `gateway-check.sh` estendido para fazer o `TokenRevoked` vir de um **logout real** (depende do auth, RFC 0007).

## 7. Docs

Corrigir o link do ADR-0012 no README; documentar a lista pública na tabela de API do README.

## 8. Out of scope

CORS, TLS, rotação de chave automática (o gateway já tolera `previous` no JWKS), observabilidade de métricas.

## 9. Risks

- **Fail-open lido como falha de segurança:** já aceito (ADR-0014), limitado a 15 min; explicar na apresentação.
- **Sem o `refresh` público, o fluxo de sessão do RFC 0002 não fecha** e a rota fica com 401 mesmo com o auth pronto. Corrigir antes do `auth-check.sh`.
- **Cold start:** se o gateway sobe antes do auth, as primeiras requisições protegidas falham; o compose deve ordenar o `depends_on` (verificar no `docker-compose.yml`).

## 10. Unresolved questions

1. Sem JWKS disponível, 401 ou 503? Hoje 401; 503 distingue falha de infraestrutura de token inválido. Dono: Pedro. Não bloqueia.
2. `X-User-Email` é injetado (RFC 0002), mas nenhum serviço o consome; manter por compatibilidade com o RFC.

## 11. Decisions

| # | Grade | Decision |
|---|---|---|
| 1 | `LOCKED` | Validação local por JWKS e blacklist por evento (ADR-0002/0014); nada de introspecção. |
| 2 | `LOCKED` | Headers de identidade do cliente são sempre removidos e reinjetados. |
| 3 | `ASSUMED` | `POST /auth/refresh` é pública (o RFC 0002 §5.5 já a lista); a lista pública é um mapa explícito, com teste. |
| 4 | `ASSUMED` | `golang-jwt/jwt/v5` fecha a Decisão 8 do RFC 0002. |
| 5 | `OPEN` | **Substituída pela #7.** JWKS indisponível: 401 (atual) ou 503. |
| 6 | `ASSUMED` | Sem CORS enquanto não houver frontend. |
| 7 | `ASSUMED` | Resolve a #5 (24/11, Pedro): com o JWKS indisponível, as rotas protegidas **continuam respondendo 401**, e o log diz por quê. Sem mudança de comportamento no freeze. Responder 503 distinguiria falha de infraestrutura de token inválido e fica como melhoria futura (tarefa G05 do plano). |

## 12. Phasing

- **P1:** `refresh` público + testes de superfície + README.
- **P2:** `gateway-check.sh` com logout real, quando o auth tiver `logout`.
