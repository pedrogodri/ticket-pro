# user-service

Perfil do usuário e read models "meus ingressos" e "minhas compras" do Ticket Pro, em C# com .NET 10. É o bônus de CQRS: os read models são montados só a partir de eventos, sem consultar outros serviços.

**Dona:** Yasmin.

**Estado:** implementado.
- **Cadastro:** perfil projetado de `UserRegistered`, com compensação por `UserCreationFailed`.
- **Read models:** `my_tickets` (de `TicketsIssued`) e `my_orders` (de `OrderConfirmed`).
- **API:** `GET /me`, `GET /me/tickets` e `GET /me/orders`, com RLS por `app.user_id`.

## Responsabilidades

- **Saga de cadastro** ([arquitetura §5](../../docs/arquitetura.md)):
  - ao consumir `UserRegistered`, cria o perfil;
  - se o perfil não puder ser criado, publica `UserCreationFailed` pelo outbox, na mesma transação, e o auth compensa removendo a credencial.
- **Read models (CQRS):**
  - `my_tickets`, montado a partir de `TicketsIssued`;
  - `my_orders`, montado a partir de `OrderConfirmed`.
- **BOLA:** o usuário B nunca vê dados de A. A rota usa só `X-User-Id`, e o banco filtra por RLS com `app.user_id` ([padrões §6.1](../../docs/padroes.md)).

## API (via gateway)

| Endpoint | Resposta |
|---|---|
| `GET /health/live` | `200 {"status":"up"}` |
| `GET /health/ready` | `200 {"status":"up","checks":{"db":"up","broker":"up"}}`. Broker fora → `"broker":"degraded"` (continua 200). Banco fora → `503`. |
| `GET /me` | `200 {userId, email, name, createdAt}`. `404 NOT_FOUND` enquanto o `UserRegistered` não foi projetado (consistência eventual, alguns instantes após o cadastro). |
| `GET /me/tickets?limit=&cursor=` | `200 {items:[{ticketId, code, orderId, eventId, eventName, eventStartsAt, sectorName, issuedAt}], nextCursor}`, do mais novo para o mais antigo. |
| `GET /me/orders?limit=&cursor=` | `200 {items:[{orderId, eventId, sectorId, quantity, totalCents, currency, confirmedAt, ticketsIssued}], nextCursor}`. Só compras **confirmadas**. `ticketsIssued < quantity` quer dizer que os ingressos ainda estão a caminho. |

Regras comuns (RFC 0002 §5.6, padrões §4):
- **Identidade:** sem `X-User-Id` válido (UUID), a resposta é `401 UNAUTHENTICATED`. Não existe rota com `userId` na URL.
- **Paginação:** por cursor opaco (keyset). `limit` vai de 1 a `PAGE_MAX_LIMIT` (padrão 20, máximo 50). Um `limit` ou `cursor` inválido, ou qualquer parâmetro desconhecido, dá `400 VALIDATION_ERROR`.
- **Erros:** em `application/problem+json` (RFC 9457), com `code` e `correlationId`, a partir do catálogo em `Http/Errors.cs`.

O **status em tempo real de um pedido** (`PENDING`, `CANCELLED`...) é do order-service (`GET /orders/{id}`). O `/me/orders` é um read model e só sabe de pedidos confirmados.

## Eventos

| Publica (tópico `ticketpro-users`) | Consome (fila `ticketpro-user-service`) |
|---|---|
| `UserCreationFailed` (`INVALID_PROFILE_DATA`, `DUPLICATE_PROFILE`) | `UserRegistered`, `OrderConfirmed`, `TicketsIssued` (todos v1) |

| Evento consumido | Efeito |
|---|---|
| `UserRegistered` | Se o perfil for válido, grava em `profiles`. Se não for (nome em branco, caractere de controle, domínio em `PROFILE_BLOCKED_EMAIL_DOMAINS`), publica `UserCreationFailed(INVALID_PROFILE_DATA)`. Se o e-mail já pertencer a **outro** `user_id`, publica `UserCreationFailed(DUPLICATE_PROFILE)`. O mesmo `user_id` de novo é no-op e nunca compensa, porque compensar apagaria uma conta legítima. |
| `OrderConfirmed` | Uma linha em `my_orders` (`order_id` PK). |
| `TicketsIssued` | Uma linha por ingresso em `my_tickets` (`ticket_id` PK, `ticket_code` UNIQUE). `issuedAt` é o `occurredAt` do envelope. |

Payload fora do schema e versão desconhecida são poison: depois de 3 recebimentos a mensagem vai para a DLQ, e as demais seguem.

## Dados

O banco é `users`, no container `users-db`.

| Tabela | Conteúdo |
|---|---|
| `profiles` | `user_id` PK, e-mail único sem diferenciar maiúsculas (V0006) |
| `my_tickets` | read model "meus ingressos" |
| `my_orders` | read model "minhas compras" (V0005) |
| `outbox`, `processed_messages` | plataforma |

As três tabelas por usuário têm `ENABLE` + `FORCE ROW LEVEL SECURITY`. A V0008 faz `app.user_id` ausente significar "nenhuma linha" mesmo numa conexão reaproveitada do pool.

## Código (organizado por camada)

Este serviço é organizado **por camada**, e não por módulo como pede o [padrões §2](../../docs/padroes.md). Foi uma decisão da dona: com só dois assuntos pequenos (perfil e read models), a árvore fica mais rasa e as camadas ficam visíveis na raiz. As regras do §2 continuam valendo:
- Domain não importa Infrastructure nem framework;
- consumidores e endpoints só traduzem a entrada e chamam o caso de uso.

```
src/UserService/
├── Program.cs        # liga config, logs, 2 data sources, SQS, relay, health e /me*
├── App/              # AppConfig (valida env no boot), HealthEndpoints, PostgresUrl, loops em background
├── Domain/           # regras puras: ProfileRules, EmailMask, Profile, MyTicket, MyOrder
├── Application/      # casos de uso e portas: RegisterProfile, ProjectTicketsIssued, ProjectOrderConfirmed,
│                     # UserQueries, paginação por cursor, IProfileStore/IUserReadStore/IUserEventPublisher
├── Infrastructure/   # SQL (ProfileStore, ProjectionStore, UserReadStore com RLS), outbox e records das mensagens
├── Consumers/        # IMessageHandler de UserRegistered, OrderConfirmed e TicketsIssued
├── Http/             # /me, /me/tickets, /me/orders, X-User-Id, catálogo de erros, problem+json
└── Platform/         # GERADO por scripts/sync-shared.sh a partir de shared/dotnet: não edite
tests/UserService.Tests/
├── Unit/             # config, regras do domínio, casos de uso, paginação, health
├── Http/             # pipeline HTTP real em memória (TestServer)
├── Contract/         # exemplos e eventos produzidos validados contra contracts/
└── Integration/      # Testcontainers: Postgres real com as migrations, SqsConsumer real, RLS
```

## Como atende aos requisitos do trabalho

| Requisito | Onde |
|---|---|
| Saga com compensação | Cadastro: `RegisterProfile` publica `UserCreationFailed` na mesma transação; o auth remove a credencial. |
| Evento com vários interessados | `TicketsIssued` (user + notification) e `OrderConfirmed` (user + inventory). |
| **R1** idempotência | `processed_messages` na mesma TX do efeito (`SqsConsumer`), mais chaves naturais com `ON CONFLICT DO NOTHING` como segunda barreira. Teste `F3_same_message_five_times_has_a_single_effect`. |
| **R4** consumidores concorrentes | `deploy.replicas: 2` no `compose.yaml`. As projeções não dependem da ordem de chegada: cada evento grava na sua tabela e a leitura faz a junção. Testes `R4_*` e `Arrival_order_*`. |
| **R6** reconexão | Consumidor e relay com backoff (`Platform/`). O readiness mostra `broker: degraded` e o outbox drena quando o broker volta. |
| **F2** kill no meio | O ack só sai depois do commit; mensagem sem ack volta para a fila e vai para a outra réplica. |
| **F3** duplicata | `duplicate=true` no log e um único efeito. |
| **F4** veneno | Schema inválido → `poison=true` → DLQ após 3 recebimentos. |
| **BOLA** | Teste `ReadStoreTests`: B não vê nada de A, nem com query sem `WHERE`. |
| Bônus CQRS | `my_tickets` e `my_orders`, montados só de eventos. |

**Para demonstrar a compensação do cadastro:** registre um usuário com e-mail `@bloqueado.test`. O log mostra `compensation_requested`, e o `UserCreationFailed` sai pelo outbox para o auth.

## Variáveis de ambiente

| Variável | Obrigatória | Exemplo |
|---|---|---|
| `SERVICE_NAME` | sim | `user-service` |
| `PORT` | sim | `8080` |
| `LOG_LEVEL` | sim | `debug` \| `info` \| `warn` \| `error` |
| `DATABASE_URL` | sim | `postgres://users_app:…@users-db:5432/users` (API, RLS) |
| `DATABASE_WORKER_URL` | sim | role `users_worker` (relay, consumidores) |
| `USERS_TOPIC_ARN` | sim | `arn:aws:sns:us-east-1:000000000000:ticketpro-users` |
| `AWS_REGION`, `QUEUE_URL`, `CONTRACTS_DIR` | sim | `us-east-1`, fila `ticketpro-user-service`, `/contracts` |
| `AWS_ENDPOINT_URL` | não | `http://ministack:4566` (sem ele, AWS real) |
| `OUTBOX_POLL_INTERVAL_MS`, `OUTBOX_BATCH_SIZE`, `OUTBOX_MAX_BACKOFF_MS`, `OUTBOX_CLAIM_LEASE_MS`, `HTTP_CLIENT_TIMEOUT_MS` | não | 200, 50, 30000, 30000, 3000 (RFC 0003 §5.1) |
| `CONSUMER_CONCURRENCY`, `SQS_WAIT_TIME_S` | não | 4, 10 (≤ 20) |
| `PROFILE_BLOCKED_EMAIL_DOMAINS` | não | `bloqueado.test`: domínios recusados no cadastro, separados por vírgula, subdomínios incluídos. Vazio = nenhum. |
| `PAGE_DEFAULT_LIMIT`, `PAGE_MAX_LIMIT` | não | 20, 50 (o máximo não passa de 50) |

Toda variável é validada no boot: valor inválido impede a subida com `invalid configuration: <VARIÁVEL>: ...`.

O banco e o job de migration usam `db.env.example` (owner e senhas dos roles, só desenvolvimento). `src/UserService/Platform/` e `contracts/` são cópias geradas por `scripts/sync-shared.sh`: não edite.

## Comandos

```bash
make test               # xUnit: unitários, HTTP e contrato
make test-integration   # Testcontainers: Postgres real com as migrations deste serviço
make lint               # dotnet format --verify-no-changes
make typecheck          # build com warnings como erro
make run                # sobe só este serviço
```

Sem o .NET SDK instalado, os alvos rodam sozinhos no container `mcr.microsoft.com/dotnet/sdk:10.0`. Para forçar: `make test TOOLCHAIN=docker`. Também há um Dev Container em [`.devcontainer/`](.devcontainer/devcontainer.json).
