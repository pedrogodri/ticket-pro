# F2 Caminho feliz — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Um cliente consegue, só pelo gateway: cadastrar-se, fazer login, listar eventos e comprar ingressos. A compra é assíncrona (202) e a saga a leva até `CONFIRMED`. No fim, o usuário vê os ingressos em `GET /me/tickets` e o comprovante existe no S3.

**Architecture:** Cada serviço ganha o seu domínio sobre a plataforma da F1:

- consumidores ligados por um `SqsConsumer` próprio;
- publicação de eventos só via outbox;
- API HTTP com `X-User-Id` injetado pelo gateway e RLS por `app.user_id`.

O gateway valida o JWT RS256 do auth via JWKS e roteia por prefixo. Order, inventory e payment rodam com 2 réplicas.

**Tech Stack:**
- Plataforma e ferramentas da F1 (`shared/go`, `shared/dotnet`, `platform/` em Java e em TS).
- Novas dependências:
  - **gateway:** `github.com/golang-jwt/jwt/v5`.
  - **auth:** `spring-security-crypto` + `bcprov` (Argon2id), `nimbus-jose-jwt`, AWS SDK `secretsmanager`.
  - **notification:** `aws-sdk-go-v2/service/s3`.

**Spec:**
- `docs/roadmap.md` §F2 (tarefas + checkpoint)
- `docs/arquitetura.md` §3, §5, §6 (saga e máquina de estados), §7, §8
- `docs/rfc/0002-authentication-and-authorization.md` §5.1–5.6
- `docs/rfc/0001-message-contract-and-event-versioning.md` §5.6
- `docs/rfc/0003-failure-handling-strategy.md` §5.1, §5.4, §5.6
- `docs/padroes.md` §4 (HTTP, paginação, erros), §6 (RLS, IDs, dinheiro)
- `contracts/` (payloads exatos)

## Global Constraints

- **Branch:** `feat/f2-happy-path`, a partir de `feat/f1-platform`. Sem commit na `main`, sem push, sem worktree.
- **Dependências:** versão estável ≥ 2 semanas, travada no lockfile. Go continua em 1.25: se a versão nova exigir `go 1.26`, use a anterior.
- **Payloads:** exatamente os de `contracts/events/<Type>/v1.schema.json`. Todo evento produzido tem teste que o valida contra o `contracts/` vendorizado do serviço.
- **Dinheiro:** centavos inteiros + `currency` (`"BRL"`). O preço vem do seed do inventory, nunca do cliente.
- **IDs:** UUID gerado pela aplicação (v7 onde a lib da F1 já gera). `correlationId` do evento = `X-Correlation-Id` da requisição, ou o `correlationId` do evento de entrada.
- **Identidade:**
  - os serviços leem o usuário só de `X-User-Id`;
  - rota autenticada sem o header → `401 UNAUTHENTICATED`;
  - objeto de outro usuário → `404 NOT_FOUND`.
- **Erros HTTP:** `application/problem+json` no formato de padrões §4.1. O `title` fica em PT-BR, num catálogo por serviço (`errors.go`, `Errors.cs`, `messages.properties`, `errors.ts`).
- **RLS:**
  - a API usa o role `*_app` e abre cada transação com `SET LOCAL app.user_id = '<X-User-Id>'`;
  - consumidores, relay e workers usam `*_worker`;
  - tabelas com dado por usuário têm `ENABLE` + `FORCE ROW LEVEL SECURITY` e a policy de padrões §6.1.
- **Migrations:** novas tabelas a partir de `V0004__…` em cada serviço. Seed em migration própria, separada do DDL. Nunca editar V0001–V0003.
- **Consumidor de cada serviço** (wiring igual nas 4 linguagens):
  - `SqsConsumer` da F1, pool worker, validator sobre `CONTRACTS_DIR`;
  - nome = `SERVICE_NAME`;
  - envs `QUEUE_URL`, `CONSUMER_CONCURRENCY` (4) e `SQS_WAIT_TIME_S` (10);
  - cliente SQS com o timeout de long poll da F1;
  - readiness com `broker:"up"` só se relay **e** consumidor estão up.
- **Handlers:**
  - todo tipo que chega à fila do serviço (filter policy, arquitetura §7) tem handler, mesmo que seja só registrar. Tipo sem handler vira poison e cai na DLQ;
  - transições de estado sempre como `UPDATE … WHERE id = $1 AND status = <esperado>`. 0 linhas afetadas → `ErrStale` (stale);
  - versão suportada: só `1` nesta fase. O `OrderCreated` v2 e o upcaster ficam para a F4.
- **Env novas** (no `.env.example` do serviço que usa):

  | Variável | Padrão | Serviço |
  |---|---|---|
  | `JWT_ISSUER` / `JWT_AUDIENCE` | `ticketpro-auth` / `ticketpro-api` | auth, gateway |
  | `JWT_ACCESS_TTL_S` / `JWT_REFRESH_TTL_S` | 900 / 604800 | auth |
  | `JWT_SECRET_ID` | `ticketpro/auth/jwt-signing-key` | auth |
  | `JWT_KEY_REFRESH_S` | 300 | auth |
  | `ARGON2_MEMORY_KB` / `ARGON2_ITERATIONS` / `ARGON2_PARALLELISM` | 19456 / 2 / 1 | auth |
  | `GATEWAY_ROUTES` | `auth=http://auth-service:8080,me=http://user-service:8080,orders=http://order-service:8080,events=http://inventory-service:8080` | gateway |
  | `JWKS_URL` | `http://auth-service:8080/.well-known/jwks.json` | gateway |
  | `JWKS_CACHE_TTL_S` / `JWT_CLOCK_SKEW_S` | 300 / 30 | gateway |
  | `GATEWAY_MAX_BODY_BYTES` / `GATEWAY_UPSTREAM_TIMEOUT_MS` | 65536 / 5000 | gateway |
  | `ORDER_PAYMENT_TIMEOUT_MS` | 15000 | order |
  | `ORDER_MAX_QUANTITY` | 6 | order |
  | `RESERVATION_TTL_MS` | 60000 | inventory |
  | `PAYMENT_LATENCY_MS` / `PAYMENT_LATENCY_JITTER_MS` | 500 / 300 | payment |
  | `PAYMENT_FAILURE_RATE` | 0.1 | payment |
  | `PAYMENT_PROVIDER_TIMEOUT_MS` | 8000 | payment |
  | `PAYMENT_WORKER_LEASE_MS` / `PAYMENT_WORKER_POLL_MS` | 30000 / 200 | payment |
  | `RECEIPTS_BUCKET` | `ticketpro-receipts` | notification |

- **Fora da F2** (ficam para a F3, não implementar):
  - `/auth/refresh` e `/auth/logout`;
  - `TokenRevoked` → Redis do gateway e rate limit;
  - deadline scanner do order e estorno tardio (`RefundRequested`, `PaymentRefunded`);
  - job de expiração de reservas e `InventoryReservationExpired`;
  - `UserCreationFailed` e a compensação do cadastro;
  - validação `RESERVATION_TTL ≥ timeout + margem` no boot.

  O `login` já grava o refresh token (hash) para a F3 não precisar de migration nova.
- **Commits:** Conventional com escopo da pasta, terminando com `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Requisição sem token ou com token adulterado** (alg `none`, HS256 com a chave pública, `kid` desconhecido, expirado, `aud` errado) → `401`, nunca encaminhada. Testes na Task 2.
2. **Cliente forjando identidade.** `X-User-Id` enviado pelo cliente é removido, e `userId` no corpo de `POST /orders` → `400`. Testes nas Tasks 2 e 5.
3. **Retry do cliente em `POST /orders` com a mesma `Idempotency-Key`** → mesmo pedido (`200` com o existente), nunca dois. A mesma chave de outro usuário cria um pedido próprio. Teste na Task 5.
4. **Usuário B consultando o pedido de A** → `404`. Isso vale mesmo se a query esquecer o filtro, por causa do RLS. Teste na Task 5, contra o banco real.
5. **Corrida pelo último ingresso** (R2): 100 reservas concorrentes num setor com 10 → exatamente 10 `InventoryReserved`, 90 `InventoryRejected` e `available = 0`. Teste na Task 4.

---

## File Structure (o que cada task cria ou altera)

```
services/auth-service/      modules/{registration,session,keys}  + migrations V0004 credentials, V0005 refresh_tokens
services/gateway/           internal/{proxy,jwtauth,problem}     + config de rotas
services/inventory-service/ internal/modules/{catalog,reservations,tickets} + V0004 catálogo, V0005 seed
services/order-service/     src/modules/orders/{http,application,domain,infrastructure,consumers} + V0004 orders
services/payment-service/   Modules/Payments/{Consumers,Workers,Provider,Infrastructure} + V0004 payments/provider_charges
services/user-service/      Modules/Profiles, Modules/MyTickets  + V0004 profiles, my_tickets
services/notification-service/ internal/modules/receipts         + V0003 notifications
scripts/happy-path.sh, scripts/check-replicas.sh
```

---

### Task 1: auth-service — cadastro, login e JWKS (Felipe)

**Files:**
- Create: `modules/registration/{web,application,infrastructure}`, `modules/session/{web,application,infrastructure}`, `modules/keys/{web,infrastructure}` (pacote `br.furb.ticketpro.auth`), `migrations/V0004__create_credentials.sql`, `migrations/V0005__create_refresh_tokens.sql`, `src/main/resources/messages.properties`, `platform/web/ProblemDetails*.java`
- Modify: `pom.xml`, `AppConfig` (+ envs JWT/Argon2/`JWT_SECRET_ID`), `.env.example`, `README.md`

**Interfaces:**
- Produces:
  - `POST /auth/register` `{email, password, name}` → `201 {userId}`. Outbox `UserRegistered{userId,email,name}` na mesma TX. E-mail duplicado → `409 CONFLICT`. Senha com menos de 8 ou mais de 128 caracteres, e-mail inválido ou campo extra → `400 VALIDATION_ERROR`.
  - `POST /auth/login` `{email, password}` → `200 {accessToken, refreshToken, expiresIn}`. Credencial errada ou e-mail inexistente → mesmo `401 UNAUTHENTICATED`, com o mesmo `title`.
  - `GET /.well-known/jwks.json` → `{keys:[{kty:"RSA",kid,use:"sig",alg:"RS256",n,e}]}` com `current` (+ `previous`, se houver).
  - Access token RS256, header `kid`, claims `iss`, `aud`, `sub`=userId, `jti`, `iat`, `exp`=iat+`JWT_ACCESS_TTL_S`, `email`.
- Tabelas:
  - `credentials(user_id uuid PK, email text UNIQUE (lower), password_hash text, status text CHECK IN ('ACTIVE'), created_at)`;
  - `refresh_tokens(id uuid PK, user_id uuid FK, family_id uuid, token_hash text UNIQUE, expires_at, used_at, revoked_at)` + índice em `user_id`.
  - As duas com RLS por `user_id`. Registro e login usam o pool worker (padrões §6.1).

- [ ] **Step 1: Testes (falham).**
  - **Unit:**
    - Argon2id com os parâmetros das envs: verify ok, senha errada falha;
    - o JWT emitido verifica com a chave pública e tem as claims acima;
    - o JWKS serializa `n`/`e` em base64url sem padding.
  - **`@WebMvcTest`:**
    - register: 201; 409 duplicado (case-insensitive); 400 para senha de 7 caracteres e para campo `role` extra;
    - login: 200; 401 igual para senha errada e para e-mail inexistente.
  - **IT (Testcontainers PG + MiniStack, segredo criado no teste no formato `{current:{kid,privatePem},previous:null}`):**
    - register grava credencial + 1 linha no outbox `UserRegistered` que valida no contrato;
    - login grava 1 `refresh_tokens` com hash SHA-256, nunca o token em claro;
    - JWKS expõe o `kid` do segredo.
- [ ] **Step 2:** `make test test-integration` → FAIL.
- [ ] **Step 3: Implementar.**
  - A chave sai do Secrets Manager no boot (`JWT_SECRET_ID`) e é relida a cada `JWT_KEY_REFRESH_S`. Segredo inexistente → o app não sobe e loga `jwt_key_unavailable`.
  - O e-mail vai mascarado nos logs (padrões §5).
- [ ] **Step 4:** `make test test-integration lint typecheck` → PASS. Na stack: `curl` (via busybox na rede backend) em `/auth/register` + `/auth/login` → 201/200. `scripts/verify-platform.sh` sem falhas.
- [ ] **Step 5: Commit** — `feat(auth-service): register, login and JWKS with keys from Secrets Manager`.

---

### Task 2: gateway — roteamento, JWT via JWKS e headers (Pedro)

**Files:**
- Create: `internal/proxy/{router,proxy}.go`, `internal/jwtauth/{jwks,verifier}.go`, `internal/problem/problem.go`, `internal/errors.go`, testes ao lado
- Modify: `internal/app/{config,server}.go`, `.env.example`, `README.md`

**Interfaces:**
- Produces:
  - **Rotas.** O primeiro segmento do path vem de `GATEWAY_ROUTES`: `/auth/*` → auth, `/me*` → user, `/orders*` → order, `/events*` → inventory. O path é encaminhado inteiro. Rota desconhecida → `404 NOT_FOUND`. `/.well-known/*` e `/health/*` dos serviços nunca são roteados.
  - **Allowlist pública:** `POST /auth/register` e `POST /auth/login`. Todo o resto exige `Authorization: Bearer`.
  - **Ordem por requisição** (RFC 0002 §5.5, sem rate limit e sem Redis nesta fase):
    1. gera `X-Correlation-Id` (UUID). O valor vindo do cliente vira `X-Client-Request-Id`;
    2. remove `X-User-Id`, `X-User-Email` e `X-Internal-*`;
    3. valida o JWT: `alg == RS256`, `kid` no JWKS em cache (`JWKS_CACHE_TTL_S`; `kid` desconhecido → refetch, no máximo 1 a cada 10 s), assinatura, `iss`, `aud`, `exp`/`nbf` com folga `JWT_CLOCK_SKEW_S`;
    4. injeta `X-User-Id`=sub, `X-User-Email`, `X-Correlation-Id` e `traceparent`;
    5. encaminha com `GATEWAY_UPSTREAM_TIMEOUT_MS`.
  - **Erros:**
    - corpo > `GATEWAY_MAX_BODY_BYTES` → `413 PAYLOAD_TOO_LARGE`;
    - upstream lento → `504 UPSTREAM_TIMEOUT`; upstream recusado → `502`;
    - 401 com `WWW-Authenticate: Bearer error="invalid_token"`.
  - **Headers de resposta autenticada:** `X-Content-Type-Options: nosniff` e `Cache-Control: no-store`.
  - **Outros:**
    - `jwtauth.NewVerifier(cfg VerifierConfig, fetch func(ctx) (JWKS, error)) *Verifier`, com `(*Verifier) Verify(ctx, token string) (Claims, error)`;
    - `Claims{Subject, Email, ID string}`.

- [ ] **Step 1: Testes (falham)**, com `httptest` como upstream e um par RSA gerado no teste:
  - token válido → upstream recebe `X-User-Id`, `X-Correlation-Id` (UUID) e o path original;
  - **Review Focus 1:** sem token; `alg:none`; HS256 assinado com a chave pública; `kid` desconhecido (e o refetch acontece no máximo 1× em 10 s); expirado além da folga; `aud` errado → 401 e upstream não chamado;
  - **Review Focus 2:** `X-User-Id: atacante` do cliente nunca chega ao upstream;
  - register sem token → encaminhado;
  - corpo de 65537 bytes → 413;
  - upstream que dorme 6 s → 504;
  - rota `/.well-known/jwks.json` → 404.
- [ ] **Step 2:** `make test` → FAIL.
- [ ] **Step 3: Implementar** com `net/http/httputil.ReverseProxy` + `golang-jwt/jwt/v5`. JWKS próprio: busca por `JWKS_URL`, mapa `kid` → `*rsa.PublicKey`.
- [ ] **Step 4:** `make test lint typecheck` → PASS. Na stack: `curl localhost:8080/events` sem token → 401; com o token do login da Task 1 → resposta do inventory.
- [ ] **Step 5: Commit** — `feat(gateway): route by prefix, validate RS256 JWT via JWKS and inject identity headers`.

---

### Task 3: inventory-service — catálogo e seed (Pedro)

**Files:**
- Create: `migrations/V0004__create_catalog.sql`, `migrations/V0005__seed_catalog.sql`, `internal/modules/catalog/{handler,service,repository,types}.go` + testes, `internal/httpx/{problem,identity}.go`, `internal/errors.go`
- Modify: `internal/app/server.go` (rotas)

**Interfaces:**
- Tabelas:
  - `events(id uuid PK, name, starts_at, venue)`;
  - `sectors(id uuid PK, event_id FK + índice, name, capacity int, available int, unit_price_cents bigint, currency text, CHECK (available >= 0 AND available <= capacity), CHECK (unit_price_cents > 0))`.
  - Catálogo sem RLS: é público para usuários logados.
- **Seed:** 2 eventos com ids fixos (UUID literal no SQL):
  - "Festival de Verão 2026": setores Pista (500 × 25000), VIP (100 × 60000) e **F5** (10 × 15000), usado no cenário F5;
  - "Show Acústico": setor Plateia (200 × 18000).
- `GET /events?limit&cursor` → `{items:[{id,name,startsAt,venue}], nextCursor}`, com `limit ≤ 50` (padrão 20) e cursor opaco (base64 de `starts_at|id`).
- `GET /events/{id}` → `{id,name,startsAt,venue,sectors:[{id,name,capacity,available,unitPriceCents,currency}]}`. Inexistente ou id inválido → 404.
- As duas exigem `X-User-Id` (401 sem ele). Produz `httpx.RequireUser(next http.Handler) http.Handler` e `httpx.WriteProblem(w, status int, code string, detail string)`, reaproveitados pelas Tasks 4 e 9 (notification copia o mesmo arquivo).

- [ ] **Step 1: Testes (falham).**
  - repositório contra PG real (Testcontainers, aplicando `migrations/` em ordem): o seed tem 2 eventos e o setor F5 com `available = 10`;
  - paginação com `limit=1` percorre os 2 eventos e `nextCursor` termina `null`;
  - handler: 401 sem `X-User-Id`; 404 para id inexistente e para `abc`; `limit=51` → 400.
- [ ] **Step 2:** `make test test-integration` → FAIL. Ruling já tomado: o inventory ganha `make test-integration` com build tag `integration`, igual à `shared/go`.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** `make test test-integration lint typecheck` → PASS. Stack: migrate aplica V0004/V0005 e o `GET /events` via gateway lista os 2 eventos.
- [ ] **Step 5: Commit** — `feat(inventory-service): event catalog with seed and paginated API`.

---

### Task 4: inventory-service — reservas, emissão e R2 (Pedro)

**Files:**
- Create: `migrations/V0006__create_reservations_tickets.sql`, `internal/modules/reservations/{consumer,service,repository,types}.go`, `internal/modules/tickets/{consumer,service,repository,code}.go` + testes
- Modify: `cmd/inventory-service/main.go` (consumidor), `internal/app/{config,health}.go`

**Interfaces:**
- Tabelas:
  - `reservations(id uuid PK, order_id uuid UNIQUE, sector_id FK + índice, quantity int CHECK > 0, unit_price_cents, total_cents, status CHECK IN ('PENDING','CONFIRMED','RELEASED'), expires_at, created_at)`;
  - `tickets(id uuid PK, reservation_id FK + índice, code text UNIQUE, created_at)`.
- **`OrderCreated` v1** (sem `ON CONFLICT` em reservations):
  - `UPDATE sectors SET available = available - $q WHERE id = $s AND available >= $q`;
  - 1 linha → `reservations PENDING` (`expires_at = now + RESERVATION_TTL_MS`) + outbox `InventoryReserved` (preço do setor; `totalCents = quantity × unitPriceCents`);
  - 0 linhas → outbox `InventoryRejected`: `SECTOR_NOT_FOUND` se o setor não existe; `INVALID_QUANTITY` se `quantity < 1`; senão `OUT_OF_STOCK`.
- **`OrderConfirmed`:**
  - `reservations PENDING → CONFIRMED` (0 linhas → stale);
  - gera `quantity` tickets com `code` no formato `XXXX-XXXX-XXXX` (alfabeto `A-Z0-9` sem `O/0/I/1`, `crypto/rand`);
  - outbox `TicketsIssued`, com `eventName`, `eventStartsAt` e `sectorName` do catálogo e `userId` do evento.
- **`OrderCancelled`:** `reservations PENDING → RELEASED` + `available = available + quantity` na mesma TX. Sem reserva ou já final → stale.
- Produz no tópico `INVENTORY_TOPIC_ARN`.

- [ ] **Step 1: Testes (falham)** contra PG real:
  - handler `OrderCreated` com estoque → 1 reserva + `InventoryReserved` válido no contrato;
  - sem estoque → `InventoryRejected(OUT_OF_STOCK)`;
  - setor inexistente → `SECTOR_NOT_FOUND`;
  - `OrderConfirmed` → `quantity` tickets com códigos únicos + `TicketsIssued` válido;
  - `OrderConfirmed` repetido para a mesma reserva → stale e nenhum ticket extra;
  - `OrderCancelled` devolve exatamente a quantidade;
  - **Review Focus 5 / R2:** `TestR2_HundredConcurrentReservationsOnTenUnits`. 100 goroutines chamam o handler `OrderCreated` (cada uma na sua TX, pedidos distintos, `quantity` 1) no setor F5 → exatamente 10 `InventoryReserved`, 90 `InventoryRejected(OUT_OF_STOCK)`, `available = 0` e nenhum CHECK violado.
- [ ] **Step 2:** `make test-integration` → FAIL.
- [ ] **Step 3: Implementar** e ligar o consumidor no `main`: handlers `OrderCreated`, `OrderConfirmed`, `OrderCancelled`, versão 1.
- [ ] **Step 4:** `make test test-integration lint typecheck` → PASS. `scripts/verify-platform.sh` sem falhas.
- [ ] **Step 5: Commit** — `feat(inventory-service): reserve stock atomically and issue tickets on confirmation`.

---

### Task 5: order-service — API de pedidos (Matheus)

**Files:**
- Create: `src/modules/orders/{http/routes.ts,http/schemas.ts,application/createOrder.ts,application/queries.ts,domain/order.ts,infrastructure/orderRepository.ts}`, `src/app/errors.ts`, `src/app/identity.ts`, `migrations/V0004__create_orders.sql`, testes em `tests/unit/orders/*` e `tests/integration/orders.int.test.ts`
- Modify: `src/app/server.ts`, `src/main.ts`

**Interfaces:**
- Tabela `orders`:
  - colunas: `id uuid PK`, `user_id uuid`, `idempotency_key uuid`, `event_id`, `sector_id`, `quantity int CHECK (quantity BETWEEN 1 AND 6)`, `status text CHECK IN ('PENDING','AWAITING_PAYMENT','CONFIRMED','CANCELLED')`, `cancel_reason text`, `total_cents bigint`, `currency text`, `payment_deadline_at`, `created_at`, `updated_at`;
  - `UNIQUE (user_id, idempotency_key)`;
  - índices: `(user_id, created_at DESC)` e `(status, payment_deadline_at) WHERE status='AWAITING_PAYMENT'`;
  - RLS por `user_id`.
- `ORDER_MAX_QUANTITY` vale como validação de entrada (400). O CHECK do banco continua 6.
- **`POST /orders`:**
  - corpo `{eventId, sectorId, quantity}` estrito (campo extra, inclusive `userId` → 400) e header `Idempotency-Key` UUID obrigatório (400 sem ele);
  - TX com `SET LOCAL app.user_id`: insert `PENDING` + outbox `OrderCreated` v1;
  - resposta `202 {orderId, status:"PENDING"}` + `Location: /orders/{id}`;
  - mesma chave do mesmo usuário → `200` com o pedido existente.
- **`GET /orders/{id}`** → `{orderId, status, eventId, sectorId, quantity, totalCents, currency, cancelReason, createdAt}`. Inexistente ou de outro usuário → 404.
- **`GET /orders?limit&cursor`** → paginado, `limit ≤ 50`.
- Produz: `createOrder(pool: Pool, userId: string, correlationId: string, input: CreateOrderInput, idempotencyKey: string): Promise<{ order: OrderView; created: boolean }>`.

- [ ] **Step 1: Testes (falham).**
  - **Unit (zod):** `quantity` 0 e 7 → 400; `userId` no corpo → 400; sem `Idempotency-Key` → 400; sem `X-User-Id` → 401.
  - **Integração (Testcontainers PG com as migrations do serviço aplicadas como o Flyway faria, conectando como `orders_app`):**
    - POST → 202, linha `PENDING` + outbox `OrderCreated` válido no contrato;
    - **Review Focus 3:** mesma chave → 200 com o mesmo `orderId` e 1 linha só; mesma chave com outro usuário → pedido novo;
    - **Review Focus 4:** GET do pedido de A com `X-User-Id` de B → 404. Uma query **sem** `WHERE user_id` no repositório de teste também retorna 0 linhas, pelo RLS;
    - listagem paginada.
- [ ] **Step 2:** `make test test-integration` → FAIL.
- [ ] **Step 3:** implementar. Ruling já tomado: o teste aplica `migrations/` com placeholders substituídos (`${app_password}`) por um helper de teste, para exercitar o role `orders_app` real.
- [ ] **Step 4:** `make test test-integration lint typecheck` → PASS.
- [ ] **Step 5: Commit** — `feat(order-service): accept orders asynchronously with idempotency key and owner-only reads`.

---

### Task 6: order-service — orquestração da saga (Matheus)

**Files:**
- Create: `src/modules/orders/consumers/{inventoryReserved,inventoryRejected,paymentApproved,paymentFailed}.ts`, `src/modules/orders/application/saga.ts` + testes
- Modify: `src/main.ts` (consumidor, readiness com o consumidor)

**Interfaces:**
- Consome:
  - **`InventoryReserved`:** `PENDING → AWAITING_PAYMENT`, grava `total_cents`, `currency` e `payment_deadline_at = now + ORDER_PAYMENT_TIMEOUT_MS` + outbox `PaymentRequested{orderId,userId,amountCents,currency}`.
  - **`InventoryRejected`:** `PENDING → CANCELLED(OUT_OF_STOCK)` + outbox `OrderCancelled`.
  - **`PaymentApproved`:** `UPDATE … SET status='CONFIRMED' WHERE id=$1 AND status='AWAITING_PAYMENT' AND payment_deadline_at >= now()` + outbox `OrderConfirmed`. 0 linhas → stale (a resposta tardia fica para a F3).
  - **`PaymentFailed`:** `AWAITING_PAYMENT → CANCELLED(PAYMENT_FAILED)` + outbox `OrderCancelled`.
  - Tipos que só chegam na F3 (`InventoryReservationExpired`, `PaymentRefunded`) não ganham handler.
- Produz no tópico `ORDERS_TOPIC_ARN`.

- [ ] **Step 1: Testes (falham)**, integração com PG real e handler chamado direto com o envelope dos `contracts/examples`:
  - cada transição acima + o evento produzido valida no contrato;
  - evento repetido ou fora de ordem (`PaymentApproved` com o pedido `PENDING`) → stale, sem mudança de status;
  - `PaymentApproved` depois do deadline → stale, status continua `AWAITING_PAYMENT`.
- [ ] **Step 2:** `make test-integration` → FAIL.
- [ ] **Step 3:** implementar e ligar o `SqsConsumer`.
- [ ] **Step 4:** `make test test-integration lint typecheck` → PASS.
- [ ] **Step 5: Commit** — `feat(order-service): orchestrate the purchase saga from inventory and payment events`.

---

### Task 7: payment-service — consumidor, worker com lease e provedor simulado (Gabriel)

**Files:**
- Create: `Modules/Payments/{Consumers/PaymentRequestedHandler.cs, Workers/PaymentWorker.cs, Provider/IPaymentProvider.cs, Provider/SimulatedPaymentProvider.cs, Infrastructure/PaymentRepository.cs, Domain/Payment.cs}`, `App/Errors.cs`, `migrations/V0004__create_payments.sql` + testes `tests/PaymentService.Tests/{Unit,Integration}/…`
- Modify: `Program.cs` (consumidor hosted service + worker hosted service), `AppConfig` (envs `PAYMENT_*`, consumidor)

**Interfaces:**
- Tabelas:
  - `payments(id uuid PK, order_id uuid UNIQUE, amount_cents, currency, status CHECK IN ('PENDING','APPROVED','FAILED'), failure_reason, provider_reference, lease_until, created_at, updated_at)` + índice parcial `(created_at) WHERE status='PENDING'`;
  - `provider_charges(payment_id uuid PK, result text, provider_reference, created_at)`.
- **`PaymentRequested`:** insert `payments PENDING` (`ON CONFLICT (order_id) DO NOTHING`, conflito → stale) e ack. Sem chamada ao provedor no consumo (ADR-0006).
- **`PaymentWorker`** (a cada `PAYMENT_WORKER_POLL_MS`):
  - claim `PENDING` com `lease_until` nulo ou vencido, via `FOR UPDATE SKIP LOCKED LIMIT 1`, e `lease_until = now + PAYMENT_WORKER_LEASE_MS`;
  - chama `IPaymentProvider.ChargeAsync(paymentId, amount, ct)` com timeout duro `PAYMENT_PROVIDER_TIMEOUT_MS`;
  - TX: `APPROVED` + outbox `PaymentApproved`, ou `FAILED(reason)` + outbox `PaymentFailed(DECLINED | PROVIDER_ERROR | PROVIDER_TIMEOUT)`. Sem retry de cobrança (RFC 0003 §5.6).
- **`SimulatedPaymentProvider`:**
  - espera `PAYMENT_LATENCY_MS ± PAYMENT_LATENCY_JITTER_MS` e falha (`DECLINED`) com probabilidade `PAYMENT_FAILURE_RATE`;
  - é idempotente por `paymentId`: o resultado fica em `provider_charges`, e uma segunda chamada devolve o mesmo resultado;
  - o RNG e o relógio são injetáveis.
- `RefundRequested` não chega nesta fase (o order não publica). Sem handler.

- [ ] **Step 1: Testes (falham).**
  - **Unit (R5):** com taxa 0 → aprova; taxa 1 → `DECLINED`; latência medida dentro de `[L−J, L+J]` com relógio fake; timeout menor que a latência → `PROVIDER_TIMEOUT`; segunda chamada com o mesmo `paymentId` devolve o primeiro resultado sem nova espera.
  - **Integração (PG real):**
    - consumidor → 1 linha `PENDING`; mensagem repetida → stale;
    - worker processa → `APPROVED` + `PaymentApproved` válido no contrato;
    - dois workers simultâneos no mesmo pagamento → 1 cobrança (1 linha em `provider_charges`).
- [ ] **Step 2:** `make test test-integration` → FAIL. O payment ganha `make test-integration` (Category=Integration).
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** `make test test-integration lint typecheck` → PASS.
- [ ] **Step 5: Commit** — `feat(payment-service): consume payment requests and charge through a leased worker and a simulated provider`.

---

### Task 8: user-service — perfil e read model "meus ingressos" (Yasmin)

**Files:**
- Create: `Modules/Profiles/{Consumers/UserRegisteredHandler.cs, Http/MeEndpoints.cs, Infrastructure/ProfileRepository.cs}`, `Modules/MyTickets/{Consumers/TicketsIssuedHandler.cs, Consumers/OrderConfirmedHandler.cs, Http/MyTicketsEndpoints.cs, Infrastructure/MyTicketsRepository.cs}`, `App/Errors.cs`, `App/Identity.cs`, `migrations/V0004__create_profiles_my_tickets.sql` + testes
- Modify: `Program.cs`, `AppConfig`

**Interfaces:**
- Tabelas, ambas com RLS por `user_id`:
  - `profiles(user_id uuid PK, email, name, created_at)`;
  - `my_tickets(ticket_id uuid PK, ticket_code text UNIQUE, user_id + índice, order_id, event_id, event_name, event_starts_at, sector_name, issued_at)`.
- **`UserRegistered`:** insert profile (`ON CONFLICT DO NOTHING`).
- **`TicketsIssued`:** 1 linha por ticket (`ON CONFLICT (ticket_id) DO NOTHING`).
- **`OrderConfirmed`:** registrado como processado, sem efeito. Ruling: o read model vem só de `TicketsIssued`, que carrega tudo (RFC 0001 §5.6). O handler existe para a mensagem da filter policy não ir para a DLQ.
- `GET /me` → `{userId, email, name}`. Perfil ainda não projetado → 404 (consistência eventual, arquitetura §5).
- `GET /me/tickets?limit&cursor` → `{items:[{ticketId, code, orderId, eventName, eventStartsAt, sectorName}], nextCursor}`.
- As duas pelo role app com `SET LOCAL app.user_id`.

- [ ] **Step 1: Testes (falham)**, integração com PG real:
  - handlers a partir dos `contracts/examples`;
  - `TicketsIssued` repetido → nenhuma linha duplicada;
  - `GET /me/tickets` de B não vê os ingressos de A (RLS);
  - `GET /me` sem perfil → 404;
  - sem `X-User-Id` → 401.
- [ ] **Step 2:** `make test test-integration` → FAIL.
- [ ] **Step 3:** implementar e ligar o consumidor.
- [ ] **Step 4:** `make test test-integration lint typecheck` → PASS.
- [ ] **Step 5: Commit** — `feat(user-service): project profiles and my-tickets read model from events`.

---

### Task 9: notification-service — comprovante no S3 (Pedro + Matheus)

**Files:**
- Create: `migrations/V0003__create_notifications.sql`, `internal/modules/receipts/{consumer,service,repository,s3store}.go` + testes
- Modify: `cmd/notification-service/main.go` (consumidor, cliente S3), `internal/app/config.go` (`RECEIPTS_BUCKET`, envs do consumidor)

**Interfaces:**
- Tabela `notifications(id uuid PK, order_id uuid, kind text CHECK IN ('TICKETS_ISSUED','ORDER_CANCELLED'), payload jsonb, created_at, UNIQUE (order_id, kind))`.
- **`TicketsIssued`:**
  - `PutObject s3://$RECEIPTS_BUCKET/receipts/{orderId}.json` (chave determinística, reescrita idempotente, ADR-0006), **antes** de gravar a linha na TX;
  - corpo `{orderId, userId, eventName, eventStartsAt, sectorName, tickets:[{ticketId,code}], issuedAt}`.
- **`OrderCancelled`:** só a linha `ORDER_CANCELLED`.
- Cliente S3 com `UsePathStyle: true` (MiniStack).

- [ ] **Step 1: Testes (falham)**, integração com PG + MiniStack (bucket criado no teste):
  - `TicketsIssued` → objeto existe com o JSON acima + 1 linha;
  - repetido → mesmo objeto, 1 linha;
  - falha de S3 (bucket inexistente) → erro transitório, nenhuma linha.
- [ ] **Step 2:** `make test-integration` → FAIL.
- [ ] **Step 3:** implementar e ligar o consumidor.
- [ ] **Step 4:** `make test test-integration lint typecheck` → PASS.
- [ ] **Step 5: Commit** — `feat(notification-service): store purchase receipts in S3 and record notifications`.

---

### Task 10: réplicas, caminho feliz e checkpoint F2 (Matheus)

**Files:**
- Modify: `services/{order,inventory,payment}-service/compose.yaml` (`deploy.replicas: 2`, sem `container_name`), `scripts/wait-healthy.sh` (várias réplicas por serviço já são aceitas, conferir)
- Create: `scripts/happy-path.sh`, `scripts/check-replicas.sh`, `docs/demo/happy-path.md` (passo a passo curto)

**Interfaces:**
- **`scripts/happy-path.sh`:** bash + `curl` + `jq` contra `http://localhost:8080`. Sai com 0 só se todos os passos passarem e imprime `ok`/`FAIL` por passo:
  1. register de um e-mail único → 201;
  2. login → token;
  3. `GET /events` → ≥ 1 evento; `GET /events/{id}` → setor com `available > 0`;
  4. `POST /orders` (quantity 2, `Idempotency-Key` novo) → 202 + `Location`;
  5. poll de `GET /orders/{id}` a cada 500 ms, até 30 s, até status final;
  6. `CANCELLED(PAYMENT_FAILED)` (`PAYMENT_FAILURE_RATE=0.1`) → repete os passos 4–5 até 5 vezes;
  7. `CONFIRMED` → `GET /me/tickets` contém 2 ingressos desse `orderId`;
  8. o objeto `receipts/{orderId}.json` existe (`awsl s3api head-object`);
  9. a mesma `Idempotency-Key` → 200 com o mesmo `orderId`.
- **`scripts/check-replicas.sh`:** para order, inventory e payment, conta `message_consumed` por container (`docker compose logs` com prefixo) e exige ≥ 1 em cada uma das 2 réplicas, depois de rodar `happy-path.sh` 3×.

- [ ] **Step 1:** `scripts/happy-path.sh` com a stack atual → FAIL no passo que ainda faltar (depende das Tasks 1–9).
- [ ] **Step 2:** réplicas no compose. `docker compose up -d --build` + `scripts/wait-healthy.sh 400` → 11 serviços (8 + 3 réplicas extras) `healthy`.
- [ ] **Step 3: Checkpoint F2** (a partir de `docker compose down -v`):

  ```bash
  docker compose down -v && docker compose up -d --build && scripts/wait-healthy.sh 400
  scripts/happy-path.sh
  for i in 1 2 3; do scripts/happy-path.sh >/dev/null; done
  scripts/check-replicas.sh
  make -C services/inventory-service test-integration   # inclui TestR2_HundredConcurrentReservationsOnTenUnits
  scripts/verify-platform.sh
  ```

  Expected: `happy-path` com exit 0, `check-replicas` ok para os 3 serviços, R2 verde e `verify-platform` com 0 failed.
- [ ] **Step 4:** suíte de todos os serviços (`make test lint typecheck` + `test-integration` onde existe), `scripts/check-autonomy.sh` ×8 e `actionlint`.
- [ ] **Step 5: Commit** — `feat(scripts): add happy-path and replica checks for the F2 checkpoint`.
