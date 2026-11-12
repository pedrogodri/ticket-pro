# RFC 0002 — Autenticação e autorização

- **Status:** 📝 Draft (design fechado — implementação na fase 2 do roadmap)
- **Scope:** Cadastro, login, emissão e renovação de tokens, JWKS, validação no gateway, revogação, autorização por objeto nos serviços e proteções de borda (rate limit, tamanho de payload, CORS, headers de identidade). Não cobre mTLS entre serviços nem papéis administrativos (não existem).
- **Related:** [ADR-0002](../adr/0002-gateway-validates-jwt-locally-via-jwks.md), [ADR-0004](../adr/0004-database-per-service.md), [ADR-0010](../adr/0010-cloud-services-selection.md), [ADR-0014](../adr/0014-token-revocation-via-event.md), [RFC 0001](0001-message-contract-and-event-versioning.md), OWASP API Security Top 10 (2023)

---

## 1. Summary

O auth-service (Java) guarda as credenciais (hash Argon2id) e emite um **access token JWT RS256 de 15 min** e um **refresh token opaco rotativo** (7 dias), com detecção de reuso. A chave privada fica no Secrets Manager, e a pública é exposta por JWKS. O gateway (Go) valida o JWT localmente, consulta sua cópia da blacklist de `jti`, remove headers de identidade vindos do cliente e injeta `X-User-Id`/`X-Correlation-Id`. Os serviços fazem a autorização **por objeto** (BOLA) com base em `X-User-Id`.

## 2. Motivation

O enunciado exige gateway com JWT local e revogação. Os dois maiores riscos de API (OWASP API1 BOLA e API2 Broken Authentication) não se resolvem só no gateway: o order precisa garantir que o usuário A não lê o pedido do usuário B.

## 3. Current state

Sem código. Arquitetura e ADRs fechados.

## 4. Goals / Non-goals

**Goals**

- Nenhuma chamada síncrona ao auth por requisição autenticada.
- Revogação efetiva em segundos, e no máximo em 15 min no pior caso.
- Nenhum endpoint de dados de usuário vulnerável a BOLA.

**Non-goals**

- OAuth2/OIDC completo com provedores externos: o enunciado não pede e o prazo não comporta.
- RBAC/admin: não existe API administrativa (catálogo por seed).
- mTLS interno: o isolamento é por rede Docker (ADR-0004).

## 5. Design

### 5.1 Endpoints do auth (prefixo público `/auth`)

| Método e rota | Público? | Comportamento |
|---|---|---|
| `POST /auth/register` | sim | `{email, password, name}` → `201 {userId}`. Grava a credencial + outbox `UserRegistered` numa TX. E-mail duplicado → `409`. |
| `POST /auth/login` | sim | `{email, password}` → `200 {accessToken, refreshToken, expiresIn}`. Erro genérico `401 invalid_credentials`, sem revelar se o e-mail existe. |
| `POST /auth/refresh` | sim | `{refreshToken}` → novo par. Rotaciona o refresh token. Reuso de um refresh já usado → revoga a família inteira + `401`. |
| `POST /auth/logout` | não | Revoga o refresh atual e o `jti` do access atual → outbox `TokenRevoked`. `204`. |
| `GET /.well-known/jwks.json` | **interno** | Chaves públicas (`kid` atual + anterior). O gateway não roteia essa rota para fora. |

Validação de senha: no mínimo 8 caracteres, máximo 128, sem outras regras de composição (NIST 800-63B). Hash **Argon2id** (Spring Security `Argon2PasswordEncoder`), com parâmetros por env.

### 5.2 Access token (JWT)

```json
{ "alg": "RS256", "typ": "JWT", "kid": "2026-11-12-01" }
{
  "iss": "ingressoja-auth",
  "aud": "ingressoja-api",
  "sub": "<userId>",
  "jti": "<uuid>",
  "iat": 1763971812,
  "exp": 1763972712,
  "email": "ana@exemplo.com"
}
```

TTL de `JWT_ACCESS_TTL_S=900`. Não há `roles`, porque todo usuário é cliente.

### 5.3 Refresh token

Opaco, com 256 bits aleatórios em base64url. É guardado **só como hash SHA-256** em `refresh_tokens(id, user_id, family_id, token_hash UNIQUE, expires_at, used_at, revoked_at)`. Cada uso marca `used_at` e emite outro da mesma família. Se chegar um token com `used_at` preenchido, houve reuso: a família inteira é revogada e é publicado `TokenRevoked(reason=REFRESH_REUSE)` para o access vigente, quando conhecido. TTL de `JWT_REFRESH_TTL_S=604800`.

### 5.4 Chaves e JWKS

- O Secrets Manager guarda `ingressoja/auth/jwt-signing-key` = `{ "current": {kid, privatePem}, "previous": {kid, publicPem} | null }`, criado pelo Terraform (`tls_private_key`, RSA 2048).
- O auth lê no boot e, a cada `JWT_KEY_REFRESH_S`, assina com `current` e publica `current` + `previous` no JWKS.
- Rotação (manual nesta entrega): atualizar o segredo movendo `current` → `previous`. Tokens antigos continuam válidos até expirar.

### 5.5 Validação no gateway

Ordem por requisição:

1. **Gera** `X-Correlation-Id` (UUID). Se o cliente mandou um, ele vira só `X-Client-Request-Id` no log, nunca o id de correlação.
2. **Remove** sempre `X-User-Id`, `X-User-Email` e qualquer `X-Internal-*` recebidos.
3. Se a rota estiver na allowlist pública (`POST /auth/register|login|refresh`), aplica rate limit por IP e encaminha.
4. Senão, exige `Authorization: Bearer <jwt>` e valida:
   - `alg ∈ {RS256}` (rejeita `none`/HS*);
   - `kid` presente no JWKS em cache (`JWKS_CACHE_TTL_S=300`). Se o `kid` for desconhecido, faz um refetch com limite de 1/10 s;
   - assinatura, `iss`, `aud`, `exp`/`nbf` com folga de `JWT_CLOCK_SKEW_S=30`;
   - `EXISTS revoked:{jti}` no Redis do gateway (fail-open + log `revocation_check_unavailable`, ADR-0014).
5. Aplica rate limit por `sub` (token bucket no Redis: `RATE_LIMIT_RPS`, `RATE_LIMIT_BURST`). Excedeu → `429` + `Retry-After`.
6. Injeta `X-User-Id: <sub>`, `X-User-Email`, `X-Correlation-Id`, `traceparent` e encaminha.

Erros de autenticação → `401` com `application/problem+json` e `WWW-Authenticate: Bearer error="invalid_token"`. O motivo detalhado vai só para o log.

Proteções de borda: corpo máximo `GATEWAY_MAX_BODY_BYTES=65536` (`413`), timeout upstream `GATEWAY_UPSTREAM_TIMEOUT_MS=5000` (`504`), CORS por `CORS_ALLOWED_ORIGINS` (vazio = desabilitado, já que não há frontend), headers `X-Content-Type-Options: nosniff` e `Cache-Control: no-store` em respostas autenticadas.

### 5.6 Autorização nos serviços

| Serviço / rota | Regra |
|---|---|
| `GET /orders/{id}` | `WHERE id = $1 AND user_id = $X-User-Id`. Se não existe ou não é dono → **404** (não revela existência). |
| `GET /orders` | Sempre filtrado por `user_id = X-User-Id`, com paginação limitada (`limit ≤ 50`). |
| `POST /orders` | `user_id` vem **só** do header, nunca do corpo. Campos extras no corpo → `400`. |
| `GET /me`, `GET /me/tickets` | Só `X-User-Id`; não existe rota com `userId` na URL. |
| `GET /events*` | Autenticado, sem restrição por objeto (catálogo público para usuários logados). |

Os serviços **recusam** (`401`) requisições sem `X-User-Id` em rotas autenticadas. Isso é defesa em profundidade caso alguém alcance o serviço sem passar pelo gateway.

### 5.7 Cadastro e compensação

Ver [arquitetura §5](../arquitetura.md). Ao consumir `UserCreationFailed`, o auth apaga a credencial, revoga as famílias de refresh e publica `TokenRevoked(reason=ACCOUNT_REMOVED)` para tokens emitidos (o auth registra o `jti` dos access tokens emitidos em `issued_access_tokens` com TTL, só para isso).

### 5.8 Mapeamento OWASP API Top 10

| Risco | Controle |
|---|---|
| API1 BOLA | §5.6: filtro por `user_id` em toda query, 404 em objeto alheio |
| API2 Broken auth | §5.2–5.5: RS256, allowlist de `alg`, refresh rotativo com detecção de reuso, Argon2id, erro genérico |
| API3 Property-level | DTOs explícitos: resposta nunca serializa a entidade inteira, entrada rejeita campos extras |
| API4 Resource consumption | rate limit por IP/usuário, corpo ≤ 64 KB, `quantity ≤ 6`, paginação limitada, timeouts |
| API5 BFLA | Sem rotas administrativas expostas. JWKS e health internos não são roteados pelo gateway |
| API6 Sensitive flows | Rate limit mais estrito em `/auth/login` (`AUTH_LOGIN_RPS`) contra brute force e em `POST /orders` contra scalping |
| API8 Misconfiguration | Config por IaC + env, sem segredo no repo, só o gateway com porta publicada |
| API9 Inventory | Rotas declaradas numa tabela única no gateway + `docs/arquitetura.md §3` |
| API10 Unsafe consumption | O payment trata a resposta do provedor simulado com timeout e validação |

## 6. Tests

- auth: login válido/inválido, refresh rotativo, reuso → família revogada, logout → `TokenRevoked` no outbox.
- gateway: token expirado, `alg=none`, `kid` desconhecido, assinatura inválida, `aud` errado → 401; `X-User-Id` forjado é sobrescrito; `jti` revogado → 401; Redis fora → passa com log.
- order/user: usuário B recebe 404 no pedido do usuário A (teste de BOLA obrigatório).

## 7. Docs

O runbook mostra o fluxo login → compra → logout → token recusado. O README lista as variáveis `JWT_*`, `RATE_LIMIT_*`.

## 8. Out of scope

- Rotação automática de chave (o segredo suporta, mas o job não é construído).
- MFA, recuperação de senha, verificação de e-mail.

## 9. Risks

- **Fail-open da revogação** pode ser lido como falha de segurança. A aceitação é explícita (ADR-0014), limitada a 15 min, e precisa ser explicada na apresentação.
- **Confiança em `X-User-Id`** depende de nenhum serviço ter porta publicada. Mitigação: o compose só publica a porta do gateway, e um teste verifica isso no CI (`docker compose config`).

## 10. Unresolved questions

Nenhuma bloqueante.

## 11. Decisions

| # | Grade | Decision |
| --- | --- | --- |
| 1 | `LOCKED` | Access token JWT RS256 de 15 min validado localmente no gateway via JWKS. A troca para HMAC ou introspecção anula o ADR-0002. |
| 2 | `LOCKED` | O gateway sempre remove headers de identidade do cliente e injeta `X-User-Id`/`X-Correlation-Id`. Os serviços confiam só nesses headers. |
| 3 | `LOCKED` | Autorização por objeto nos serviços: objeto alheio → 404. |
| 4 | `ASSUMED` | Refresh token opaco, rotativo, com hash SHA-256 e detecção de reuso por família. |
| 5 | `ASSUMED` | Argon2id para senha; política mínima de 8 caracteres, sem regras de composição. |
| 6 | `ASSUMED` | Fail-open no Redis do gateway (revogação e rate limit). |
| 7 | `ASSUMED` | Chave RSA 2048 gerada pelo Terraform e guardada no Secrets Manager no formato `{current, previous}`. |
| 8 | `OPEN` | Biblioteca JWT do gateway Go (`golang-jwt/jwt/v5` + `MicahParks/keyfunc` vs `lestrrat-go/jwx`). Escolher a que suportar JWKS com refresh por `kid` desconhecido. |
| 9 | `OPEN` | Valores de rate limit por rota. Calibrar na fase de testes para que o F5 (100 req) não seja barrado pelo limite: o script do F5 usa N usuários ou o limite de `POST /orders` é ajustado por env. |

## 12. Phasing

- **P1:** auth register/login/JWKS + gateway com validação e injeção de headers.
- **P2:** refresh rotativo, logout, `TokenRevoked` → Redis do gateway.
- **P3:** rate limit por rota e testes de BOLA.
