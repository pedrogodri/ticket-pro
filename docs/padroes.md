# Padrões e convenções — Ticket Pro

> Vale para qualquer pessoa ou agente que escreva código neste repositório. As decisões estão em [`adr/`](adr/README.md), os protocolos em [`rfc/`](rfc/INDEX.md) e as mensagens em [`../contracts/`](../contracts/README.md). Se algo aqui conflitar com um ADR, o ADR vale, e este arquivo deve ser corrigido.

## 1. Estrutura do repositório

Monorepo em que cada serviço é tratado como um **repositório separado** (ADR-0016).

```
ticket-pro/
├── AGENTS.md                  # regras para agentes e devs
├── README.md                  # como subir e demonstrar
├── docker-compose.yml         # orquestra tudo: include dos compose.yaml dos serviços + infra
├── .env.example               # variáveis globais da demo (sem segredos reais)
├── contracts/                 # fonte da verdade das mensagens (RFC 0001) — VERSION
├── shared/
│   ├── go/                    # lib de plataforma Go — VERSION (ADR-0017)
│   └── dotnet/                # lib de plataforma .NET — VERSION (ADR-0017)
├── infra/
│   ├── terraform/             # SNS, SQS+DLQ, S3, Secrets Manager (ADR-0011)
│   ├── otel/                  # config do OTel Collector
│   └── compose/               # compose.yaml da infra (ministack, infra-init, otel, jaeger)
├── services/
│   ├── gateway/               # Go
│   ├── auth-service/          # Java
│   ├── user-service/          # C#
│   ├── order-service/         # Java
│   ├── inventory-service/     # Java
│   ├── payment-service/       # C#
│   ├── notification-service/  # Go
│   └── autoscaler/            # Go
├── scripts/                   # sync-shared.sh, dlq.sh, cenários f1..f8
├── docs/
└── .github/workflows/         # um workflow por serviço (filtro de caminho) + root.yml
```

### 1.1 Regras de autonomia de um serviço

- O contexto de build Docker é **a própria pasta** do serviço. É proibido referenciar `../` em código, Dockerfile, build file ou teste.
- Cada serviço tem manifesto e lockfile próprios. Não existem workspaces na raiz.
- Arquivos obrigatórios em toda pasta de serviço:

| Arquivo | Conteúdo |
|---|---|
| `README.md` | O que o serviço faz, endpoints, eventos publicados/consumidos, variáveis de ambiente e como rodar e testar sozinho |
| `Dockerfile` + `.dockerignore` | Multi-stage, usuário não-root (§9) |
| `compose.yaml` | O serviço, seu banco, seu job de migration, redes e healthchecks (incluído pela raiz) |
| `.env.example` | Todas as variáveis com valores de demo |
| `Makefile` | Alvos padronizados (§1.2) |
| `migrations/` | SQL versionado (§6), exceto gateway e autoscaler |
| `contracts/` | Cópia vendorizada dos schemas que o serviço usa (`.vendored.json`) |
| `tests/` ou equivalente da linguagem | unit, integration, contract |

- Teste de autonomia: `cp -r services/<svc> /tmp/x && cd /tmp/x && make test && docker build .` precisa funcionar.

### 1.2 Interface de comandos (igual em todo serviço)

| Alvo | Faz |
|---|---|
| `make build` | Compila |
| `make test` | Testes unitários + contrato |
| `make test-integration` | Testes com Testcontainers (Postgres, MiniStack) |
| `make lint` | Linter + formatação (modo check) |
| `make fmt` | Formata |
| `make typecheck` | Checagem de tipos (Go: `go vet`; C#/Java: build com warnings como erro) |
| `make run` | Sobe o serviço e as dependências com o `compose.yaml` local |

## 2. Estrutura interna (livre por serviço)

Não há princípio comum de organização interna ([ADR-0023](adr/0023-internal-architecture-chosen-per-service.md)). O dono de cada serviço escolhe a arquitetura que serve melhor ao serviço e à linguagem (camadas, Clean Architecture, hexagonal, *vertical slices*, pacote por funcionalidade ou outra) e registra a escolha, com o motivo, no README do serviço, na seção "Arquitetura" ou "Código".

Continua igual em todos, porque não é arquitetura interna:

- **Contrato externo:** envelope e `contracts/` (RFC 0001), publicação só pelo outbox, consumo com `processed_messages` e ack depois do commit (RFC 0003 §5.4), logs do §5, health do §9, configuração do §8 e erros do §4.1.
- **Autonomia:** cada `services/<svc>` compila e sobe sozinho (ADR-0016).
- **Caminhos que as ferramentas leem:** os arquivos e alvos do §1; `migrations/` em quem tem banco; `contracts/` em quem produz ou consome eventos; e a plataforma: vendorizada em Go e C#, sem edição (ADR-0017); própria em Java, no pacote `br.furb.ticketpro.<svc>.platform`, comparado pelo `scripts/check-java-platform.sh` (ADR-0022).

As árvores abaixo marcam o que é **fixo** em cada linguagem. O resto mostra a estrutura atual de um serviço, como exemplo; não é obrigatório.

### 2.1 Go (gateway, notification, autoscaler)

Fixo: `cmd/<svc>/main.go` e `go.mod`; `internal/platform/` (vendorizado) quando o serviço usa a plataforma; `migrations/` quando tem banco; `contracts/` quando produz ou consome eventos. O gateway não tem banco, e o autoscaler não tem banco nem plataforma. Exemplo: o notification-service.

```
services/notification-service/
├── cmd/notification-service/main.go    # wiring; subcomando `healthcheck`
├── internal/
│   ├── app/                            # config (env), server HTTP, workers, shutdown
│   ├── modules/                        # exemplo: organização escolhida pelo dono
│   │   └── receipts/                   # TicketsIssued/OrderCancelled → comprovante no S3
│   └── platform/                       # VENDORIZADO de shared/go — não edite
├── migrations/
├── contracts/                          # VENDORIZADO
├── go.mod / go.sum
└── Makefile, Dockerfile, compose.yaml, .env.example, README.md
```

Testes ficam ao lado do código (`*_test.go`). Testes de integração usam a build tag `integration`.

### 2.2 Java (auth-service, order-service, inventory-service), Spring Boot

Fixo: o pacote `br.furb.ticketpro.<svc>.platform`, `src/main/resources/application.yml` só com referências a env, `migrations/`, `contracts/`, `pom.xml` + `mvnw`. Exemplo: a estrutura planejada do auth-service.

```
services/auth-service/
├── src/main/java/br/furb/ticketpro/auth/
│   ├── AuthApplication.java
│   ├── platform/                       # envelope, outbox, idempotency, messaging, logging (próprio)
│   └── modules/                        # exemplo: organização escolhida pelo dono
│       ├── registration/  {web, application, domain, infrastructure}
│       ├── session/       {web, application, domain, infrastructure}   # login, refresh, logout
│       └── keys/          {web, application, infrastructure}           # JWKS, Secrets Manager
├── src/main/resources/application.yml  # só referências a env (${VAR})
├── src/test/java/...                   # espelha a estrutura
├── migrations/
├── contracts/
└── pom.xml (+ mvnw), Makefile, Dockerfile, compose.yaml, .env.example, README.md
```

### 2.3 C# (payment-service, user-service), .NET 10

Fixo: `src/<Projeto>/Platform/` (vendorizado), `migrations/`, `contracts/`, `Directory.Build.props` (`Nullable=enable`, `TreatWarningsAsErrors=true`). Exemplo: o payment-service.

```
services/payment-service/
├── src/PaymentService/
│   ├── Program.cs
│   ├── App/                            # configuração, DI, hosted services
│   ├── Modules/                        # exemplo: organização escolhida pelo dono
│   │   ├── Payments/  {Application, Domain, Infrastructure, Consumers, Workers}   # sem API HTTP (só health)
│   │   └── Refunds/   {Application, Domain, Infrastructure, Consumers}
│   └── Platform/                       # VENDORIZADO de shared/dotnet — não edite
├── tests/PaymentService.Tests/         # xUnit: Unit/, Integration/, Contract/
├── migrations/
├── contracts/
├── PaymentService.sln, Directory.Build.props (Nullable=enable, TreatWarningsAsErrors=true)
└── Makefile, Dockerfile, compose.yaml, .env.example, README.md
```

## 3. Nomes

| O quê | Padrão | Exemplo |
|---|---|---|
| Pasta/serviço/container | kebab-case, sufixo `-service` (exceto gateway e autoscaler) | `order-service` |
| Tópico SNS | `ticketpro-<domínio>` | `ticketpro-orders` |
| Fila SQS / DLQ | `ticketpro-<serviço>` / `ticketpro-<serviço>-dlq` | `ticketpro-payment-service-dlq` |
| Evento (`type`) | PascalCase, fato no passado ou comando substantivo | `OrderConfirmed`, `PaymentRequested` |
| Bucket / segredo | `ticketpro-<uso>` / `ticketpro/<serviço>/<nome>` | `ticketpro-receipts`, `ticketpro/auth/jwt-signing-key` |
| Banco / container de banco | `<domínio>` / `<domínio>-db` | `orders` / `orders-db` |
| Tabela | snake_case, plural | `processed_messages` |
| Coluna | snake_case. `*_id` (uuid), `*_at` (timestamptz), `*_cents` (bigint) | `payment_deadline_at` |
| Enum no banco | `text` + `CHECK (col IN (...))`, valores `UPPER_SNAKE` | `status IN ('PENDING','CONFIRMED')` |
| JSON (HTTP e payload) | camelCase | `sectorId` |
| Variável de ambiente | UPPER_SNAKE, prefixo do assunto | `PAYMENT_FAILURE_RATE` |
| Rota HTTP | plural, kebab, sem verbo | `GET /orders/{id}`, `GET /me/tickets` |

### 3.1 Variáveis de ambiente comuns (todo serviço)

| Variável | Exemplo |
|---|---|
| `SERVICE_NAME` | `order-service` |
| `PORT` | `8080` (porta interna; só o gateway é publicada) |
| `LOG_LEVEL` | `info` |
| `DATABASE_URL` | `postgres://orders_app:…@orders-db:5432/orders` (role da API, RLS aplicada) |
| `DATABASE_WORKER_URL` | `postgres://orders_worker:…@orders-db:5432/orders` (consumidores/jobs) |
| `AWS_ENDPOINT_URL` | `http://ministack:4566` |
| `AWS_REGION` | `us-east-1` |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `test` / `test` (fictícias, só MiniStack) |
| `<DOMINIO>_TOPIC_ARN` | `ORDERS_TOPIC_ARN` (um por tópico que o serviço publica) |
| `QUEUE_URL` | URL da fila do próprio serviço |
| `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_SERVICE_NAME` | `http://otel-collector:4317` / `order-service` |

As variáveis específicas (timeouts, taxas de falha, JWT, rate limit) estão nas RFCs 0002 e 0003 e no `.env.example` de cada serviço. **Nenhum** endpoint, nome de fila/tópico ou credencial fica no código.

## 4. API HTTP

- JSON em camelCase, UTF-8, datas RFC 3339 UTC, dinheiro em centavos + `currency`.
- **Assíncrono (R3):** `POST /orders` → `202 Accepted` + `Location: /orders/{id}` + corpo `{ "orderId", "status": "PENDING" }`. O cliente consulta `GET /orders/{id}`.
- **Idempotência de criação:** o header `Idempotency-Key` (UUID) é obrigatório em `POST /orders`. A mesma chave do mesmo usuário devolve o mesmo pedido (`200` com o pedido existente).
- **Paginação:** por cursor (`?limit=20&cursor=<opaco>`), com `limit ≤ 50`. A resposta é `{ "items": [...], "nextCursor": "…" | null }`.
- **Entrada:** schema validado, e campos desconhecidos → `400`.
- **Identidade:** só pelos headers `X-User-Id` e `X-Correlation-Id`, injetados pelo gateway (RFC 0002). Nunca por corpo ou query.

### 4.1 Formato de erro (RFC 9457 `application/problem+json`)

```json
{
  "type": "https://ticketpro.local/errors/validation",
  "title": "Requisição inválida",
  "status": 400,
  "detail": "quantity deve estar entre 1 e 6",
  "instance": "/orders",
  "code": "VALIDATION_ERROR",
  "correlationId": "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c",
  "errors": [{ "field": "quantity", "message": "deve ser ≤ 6" }]
}
```

| `code` | Status | Uso |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Entrada inválida |
| `UNAUTHENTICATED` | 401 | Token ausente/inválido/revogado |
| `NOT_FOUND` | 404 | Inexistente **ou de outro usuário** (BOLA) |
| `CONFLICT` | 409 | Ex.: e-mail já cadastrado |
| `PAYLOAD_TOO_LARGE` | 413 | Corpo > limite do gateway |
| `RATE_LIMITED` | 429 | + `Retry-After` |
| `INTERNAL_ERROR` | 500 | Sem stack trace na resposta (só no log) |
| `UPSTREAM_TIMEOUT` | 504 | Gateway sem resposta do serviço |

O `title` vai em PT-BR. As mensagens de erro ficam centralizadas num catálogo por serviço (`errors.ts`, `Errors.cs`, `messages.properties`, `errors.go`), e não espalhadas pelo código.

## 5. Logs estruturados e observabilidade

Uma linha JSON por evento, em stdout. Campos:

| Campo | Obrigatório | Observação |
|---|---|---|
| `timestamp` | sim | RFC 3339 UTC |
| `level` | sim | `debug` \| `info` \| `warn` \| `error` |
| `service` | sim | `SERVICE_NAME` |
| `message` | sim | Frase curta e estável (boa para grep), sem interpolar dados |
| `correlationId` | sim, quando houver contexto | Do header ou do envelope |
| `traceId` / `spanId` | sim, quando houver span | OpenTelemetry |
| `messageId`, `type`, `version` | ao consumir/publicar | |
| `orderId`, `userId` | quando aplicável | |
| `durationMs` | em fim de operação | |
| `error` | em `error` | `{kind, message}`, com stack só em `error` |

Logs padronizados que a demo usa: `message_published`, `message_consumed`, `duplicate=true`, `poison=true`, `stale_event`, `broker_down`/`broker_up`, `compensation_applied`, `late_payment_refund`.

**Nunca** logar senha, token, refresh token, chave nem corpo inteiro de requisição de auth. O e-mail aparece mascarado (`a***@exemplo.com`).

## 6. Banco de dados e migrations

- **PostgreSQL 16**, um container por serviço (ADR-0004).
- **Migrations em SQL puro com Flyway**, igual nas 3 linguagens. Cada `compose.yaml` tem um job `<svc>-migrate` (imagem `flyway/flyway` com versão fixa) que roda antes do serviço (`service_completed_successfully`).
- Nomes: `migrations/V0001__create_orders.sql`, `V0002__...`. Seeds de catálogo são migrations versionadas do inventory (`V0003__seed_catalog.sql`, incluindo o setor `F5` com 10 unidades).
- **Nunca editar uma migration aplicada.** Toda correção é uma nova migration, e o Flyway valida checksum.
- Uma mudança lógica por migration. DDL e dados em migrations separadas.
- Toda FK tem índice. Toda coluna usada em filtro frequente tem índice (parcial quando o filtro é por estado).
- Constraints no banco são a última linha de defesa de R1/R2: `UNIQUE`, `CHECK`, `NOT NULL`, `FK`.
- IDs `uuid` gerados pela aplicação (v7 preferido). Datas `timestamptz` com `DEFAULT now()`. Dinheiro em `bigint` (centavos).
- Transações curtas: nenhuma chamada de rede (HTTP, SNS, provedor) dentro de uma transação de banco.

### 6.1 Roles e RLS

| Role | Uso | Permissões |
|---|---|---|
| `<svc>_owner` | Flyway | Dono do schema (DDL) |
| `<svc>_app` | API HTTP (`DATABASE_URL`) | DML, **sujeita a RLS** |
| `<svc>_worker` | Consumidores, relay, jobs (`DATABASE_WORKER_URL`) | DML, `BYPASSRLS` (processa eventos de todos os usuários) |

As tabelas com dado por usuário (`orders`, `profiles`, `my_tickets`, `credentials`/`refresh_tokens`) têm `ENABLE` + `FORCE ROW LEVEL SECURITY`. A API faz `SET LOCAL app.user_id = '<X-User-Id>'` no início de cada transação:

```sql
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders FORCE ROW LEVEL SECURITY;
CREATE POLICY orders_owner ON orders
  USING (user_id = current_setting('app.user_id', true)::uuid)
  WITH CHECK (user_id = current_setting('app.user_id', true)::uuid);
```

Isso é defesa em profundidade contra BOLA (RFC 0002 §5.6): mesmo que uma query esqueça o `WHERE user_id`, o banco filtra. O auth usa `app.user_id` só nas rotas autenticadas (`logout`). Login e registro rodam com o role worker, porque ainda não há usuário.

### 6.2 Tabelas de plataforma (iguais em todo serviço)

```sql
CREATE TABLE outbox (
  id               bigserial PRIMARY KEY,
  message_id       uuid        NOT NULL UNIQUE,
  topic_arn        text        NOT NULL,
  type             text        NOT NULL,
  version          integer     NOT NULL CHECK (version >= 1),
  correlation_id   uuid        NOT NULL,
  traceparent      text,
  payload          jsonb       NOT NULL,
  occurred_at      timestamptz NOT NULL DEFAULT now(),
  attempts         integer     NOT NULL DEFAULT 0,
  next_attempt_at  timestamptz NOT NULL DEFAULT now(),
  published_at     timestamptz,
  last_error       text
);
CREATE INDEX outbox_pending_idx ON outbox (next_attempt_at) WHERE published_at IS NULL;
CREATE INDEX outbox_published_idx ON outbox (published_at) WHERE published_at IS NOT NULL;  -- limpeza

CREATE TABLE processed_messages (
  consumer      text        NOT NULL,
  message_id    uuid        NOT NULL,
  processed_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, message_id)
);
CREATE INDEX processed_messages_processed_at_idx ON processed_messages (processed_at);  -- limpeza
```

O claim do relay e dos jobs usa `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED LIMIT n) RETURNING *`.

## 7. Mensageria

- **Publicar só via outbox**, na mesma transação do efeito (ADR-0005). É proibido chamar `sns.Publish` direto de caso de uso.
- **Consumir com o algoritmo da RFC 0003 §5.4:** valida, abre TX, `processed_messages`, efeito + outbox, commit e só então ack.
- Os handlers declaram as versões suportadas por `type`, com upcaster para versões antigas (RFC 0001 §5.4).
- Toda mensagem nova ou alterada exige schema + exemplo + `asyncapi.yaml` em `contracts/` + `scripts/sync-shared.sh` no mesmo PR.

## 8. Configuração e segredos

- Tudo por variável de ambiente, validado no boot: o serviço **não sobe** com config inválida e loga qual variável falhou.
- `.env.example` versionado. `.env` real nunca é commitado.
- Os únicos segredos de verdade (chave JWT) ficam no Secrets Manager, criados pelo Terraform. As senhas de banco de demo ficam no `.env.example` da raiz, identificadas como "somente desenvolvimento".
- Valores de UI/negócio (limites, preços) nunca são hardcoded: vêm de env ou do banco (seed).

## 9. Docker e health check

- Dockerfile multi-stage, com imagem final mínima (distroless/alpine/`-slim`), **usuário não-root**, versões de imagem fixadas (sem `latest`) e `.dockerignore` excluindo testes, `node_modules`, `bin/obj`, `target`.
- `EXPOSE` da porta interna. Só o gateway tem `ports:` no compose.
- Health: `GET /health/live` e `GET /health/ready` (RFC 0003 §5.9). O `healthcheck` do compose chama o live. Em imagem sem shell (Go distroless), o binário tem subcomando `healthcheck`.
- Shutdown gracioso no `SIGTERM`: para de receber mensagens, termina as que estão em voo (até `SHUTDOWN_TIMEOUT_S`), fecha o pool. Mensagem sem ack volta para a fila.

## 10. Git e fluxo de trabalho

- **Nunca** commitar na `main`. Branches `<tipo>/<serviço>-<descrição>` (ex.: `feat/order-service-deadline-scanner`, `docs/architecture-foundation`).
- **Conventional Commits** com escopo = pasta: `feat(inventory-service): reserve stock atomically`, `fix(gateway): strip client identity headers`, `chore(shared): sync go platform v0.2.0`, `docs(adr): add ADR-0018`.
- Commits pequenos e focados, em inglês. PR com descrição, ADR/RFC relacionados e checklist (§11.3).
- Decisão arquitetural relevante vira **ADR**. ADR aceito não se edita, se substitui.
- Sincronização de `shared/`/`contracts/` vai num commit próprio.

## 11. Estratégia de testes

### 11.1 Camadas

| Camada | O que cobre | Ferramentas |
|---|---|---|
| **Unitário** | Domain e application: máquina de estados, cálculo de preço, classificação de erro, backoff, upcaster | Go `testing`; JUnit 5; xUnit |
| **Integração** | Repositório + SQL real (R2 com concorrência), outbox/relay, consumidor contra SQS do MiniStack | Testcontainers (Postgres 16, MiniStack) |
| **Contrato** | Eventos produzidos validam contra `contracts/` vendorizado; exemplos de `contracts/examples` alimentam os handlers | JSON Schema validator da linguagem |
| **Cenário (E2E)** | F1–F8 com o compose completo; scripts com verificação automática (exit 0/1) | `scripts/f1..f8-*.sh` (bash + curl + aws cli via container) |

### 11.2 Testes obrigatórios por requisito

| Requisito | Teste mínimo |
|---|---|
| R1 | Integração: a mesma mensagem 5× gera 1 efeito; crash antes do commit gera reentrega |
| R2 | Integração no inventory: 100 reservas concorrentes (threads) × setor com 10 dá exatamente 10 reservas e `available = 0` |
| R3 | `POST /orders` retorna 202 + `Location`; `GET` reflete a evolução do status |
| R4 | Cenário: 2 réplicas consomem a mesma fila sem efeito duplicado |
| R5 | Unitário do provedor simulado: latência e taxa de falha respeitam as envs |
| R6 | Integração: relay com broker indisponível acumula, e ao voltar drena |
| BOLA | Usuário B recebe 404 no pedido de A (order) e não vê ingressos de A (user) |

### 11.3 Fluxo

- **TDD** na implementação (skill `superpowers:test-driven-development`): teste falhando, implementação mínima, refatoração.
- Antes de abrir PR: `make lint typecheck test test-integration` verde no serviço, e `npm run validate` em `contracts/` quando mexer em contratos.
- Lint/format por linguagem: `gofmt` + `golangci-lint`; Spotless (google-java-format); `dotnet format --verify-no-changes` + analyzers com warnings como erro.
- Cobertura: sem meta global; **domain e application ≥ 80%** nos módulos de saga, reserva e pagamento.
