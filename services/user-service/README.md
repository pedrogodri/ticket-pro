# user-service

Perfil do usuário e read model "meus ingressos" do Ticket Pro, em C# com .NET 10. É o bônus de CQRS: o read model é montado só a partir de eventos, sem consultar outros serviços.

**Dona:** Yasmin.

**Estado:** F2: perfil projetado de `UserRegistered` (`GET /me`) e read model "meus ingressos" projetado de `TicketsIssued` (`GET /me/tickets`, paginado), ambos com RLS por `app.user_id`. A falha de cadastro (`UserCreationFailed`) chega na F3.

## Responsabilidades

- Criar o perfil ao consumir `UserRegistered`. Se falhar, publica `UserCreationFailed`, e o auth compensa.
- Read model `my_tickets`, montado a partir de `OrderConfirmed` + `TicketsIssued`.
- **BOLA:** o usuário B nunca vê dados de A. Isso é garantido pelo RLS com `app.user_id` ([padrões §6.1](../../docs/padroes.md)).

## API (via gateway)

| Endpoint | Fase | Resposta |
|---|---|---|
| `GET /health/live` | F0 ✅ | `200 {"status":"up"}` |
| `GET /health/ready` | F0 ✅ (`db`/`broker` na F1) | `200 {"status":"up","checks":{}}` |
| `GET /me` | F2 | perfil do usuário do token |
| `GET /me/tickets` | F2 | ingressos do usuário (read model, paginado) |

## Eventos

| Publica (tópico `ticketpro-users`) | Consome (fila `ticketpro-user-service`) |
|---|---|
| `UserCreationFailed` | `UserRegistered`, `OrderConfirmed`, `TicketsIssued` |

## Dados

O banco é `users`, no container `users-db`. Tabelas: `profiles` (`user_id PK`), `my_tickets` (`ticket_code UNIQUE`), `outbox` e `processed_messages`.

## Por onde começar

1. Ler a seção *Primeiros passos* do [README da raiz](../../README.md).
2. Ler:
   - [arquitetura](../../docs/arquitetura.md) §3, §5 (cadastro e compensação), §7 e §8;
   - [padrões](../../docs/padroes.md) §2.3 (estrutura C#) e §6.1 (RLS);
   - [RFC 0002](../../docs/rfc/0002-authentication-and-authorization.md) §5.6 (BOLA).
3. **F1:** Task 10 do [plano F1](../../docs/superpowers/plans/2026-11-12-f1-platform.md) (banco, migrations, relay e readiness). Depende da Task 8, a lib `shared/dotnet`, feita junto com o Gabriel.
4. **F2:** perfil, `GET /me` e read model `GET /me/tickets` ([roadmap](../../docs/roadmap.md)).

## Variáveis de ambiente

| Variável | Obrigatória | Exemplo |
|---|---|---|
| `SERVICE_NAME` | sim | `user-service` |
| `PORT` | sim | `8080` |
| `LOG_LEVEL` | sim | `debug` \| `info` \| `warn` \| `error` |
| `DATABASE_URL` | sim | `postgres://users_app:…@users-db:5432/users` (API, RLS) |
| `DATABASE_WORKER_URL` | sim | role `users_worker` (relay, consumidores) |
| `USERS_TOPIC_ARN` | sim | `arn:aws:sns:us-east-1:000000000000:ticketpro-users` |
| `OUTBOX_POLL_INTERVAL_MS`, `OUTBOX_BATCH_SIZE`, `OUTBOX_MAX_BACKOFF_MS`, `OUTBOX_CLAIM_LEASE_MS`, `HTTP_CLIENT_TIMEOUT_MS` | não | 200, 50, 30000, 30000, 3000 (RFC 0003 §5.1) |

O banco e o job de migration usam `db.env.example` (owner e senhas dos roles, só desenvolvimento). `src/UserService/Platform/` e `contracts/` são cópias geradas por `scripts/sync-shared.sh`: não edite.

## Comandos

```bash
make test        # xUnit
make lint        # dotnet format --verify-no-changes
make typecheck   # build com warnings como erro
make run         # sobe só este serviço
```

Sem o .NET SDK instalado, os alvos rodam sozinhos no container `mcr.microsoft.com/dotnet/sdk:10.0`. Para forçar: `make test TOOLCHAIN=docker`. Também há um Dev Container em [`.devcontainer/`](.devcontainer/devcontainer.json).
