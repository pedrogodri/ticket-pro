# payment-service

Pagamentos do Ticket Pro, em C# com .NET 10. Simula um provedor de pagamento lento e instável ([RFC 0003 §5.6](../../docs/rfc/0003-failure-handling-strategy.md)).

**Dono:** Gabriel.

**Estado:** F2: consumidor de `PaymentRequested` (grava `PENDING` e dá ack), worker com lease (`SKIP LOCKED`) e provedor simulado com latência, jitter, taxa de falha e timeout duro, publicando `PaymentApproved`/`PaymentFailed` pelo outbox. Estorno chega na F3.

## Responsabilidades

- Consumir `PaymentRequested` e gravar `payments` (`PENDING`), dando ack logo depois. A cobrança **não** acontece no consumo.
- Worker com lease (`FOR UPDATE SKIP LOCKED`) que chama o provedor simulado. O timeout é duro e não há retry de cobrança.
- **R5:** latência e taxa de falha configuráveis por env (`PAYMENT_LATENCY_MS`, `PAYMENT_FAILURE_RATE`…).
- Estorno: `RefundRequested` → `PaymentRefunded`.

## API

| Endpoint | Fase | Resposta |
|---|---|---|
| `GET /health/live` | F0 ✅ | `200 {"status":"up"}` |
| `GET /health/ready` | F0 ✅ (`db`/`broker` na F1) | `200 {"status":"up","checks":{}}` |

O serviço não tem API de negócio, só eventos.

## Eventos

| Publica (tópico `ticketpro-payments`) | Consome (fila `ticketpro-payment-service`) |
|---|---|
| `PaymentApproved`, `PaymentFailed`, `PaymentRefunded` | `PaymentRequested`, `RefundRequested` |

## Dados

O banco é `payments`, no container `payments-db`. Tabelas: `payments` (`order_id UNIQUE`), `refunds` (`payment_id UNIQUE`), `provider_charges`, `outbox` e `processed_messages`.

## Por onde começar

1. Ler a seção *Primeiros passos* do [README da raiz](../../README.md).
2. Ler:
   - [RFC 0003](../../docs/rfc/0003-failure-handling-strategy.md) §5.1, §5.4 e §5.6;
   - [ADR-0006](../../docs/adr/0006-idempotent-consumers-processed-messages.md);
   - [padrões](../../docs/padroes.md) §2.3 (estrutura C#) e §6–7.
3. **F1:** Tasks 8–9 do [plano F1](../../docs/superpowers/plans/2026-11-12-f1-platform.md):
   - a lib `shared/dotnet`, junto com a Yasmin;
   - banco, migrations, relay e readiness.
4. **F2:** consumidor + worker com lease e provedor simulado. **F3:** estorno e timeout duro ([roadmap](../../docs/roadmap.md)).

## Variáveis de ambiente

| Variável | Obrigatória | Exemplo |
|---|---|---|
| `SERVICE_NAME` | sim | `payment-service` |
| `PORT` | sim | `8080` |
| `LOG_LEVEL` | sim | `debug` \| `info` \| `warn` \| `error` |
| `DATABASE_URL` | sim | `postgres://payments_app:…@payments-db:5432/payments` (API, RLS) |
| `DATABASE_WORKER_URL` | sim | role `payments_worker` (relay, consumidores, worker) |
| `PAYMENTS_TOPIC_ARN` | sim | `arn:aws:sns:us-east-1:000000000000:ticketpro-payments` |
| `OUTBOX_POLL_INTERVAL_MS`, `OUTBOX_BATCH_SIZE`, `OUTBOX_MAX_BACKOFF_MS`, `OUTBOX_CLAIM_LEASE_MS`, `HTTP_CLIENT_TIMEOUT_MS` | não | 200, 50, 30000, 30000, 3000 (RFC 0003 §5.1) |

O banco e o job de migration usam `db.env.example` (owner e senhas dos roles, só desenvolvimento). `src/PaymentService/Platform/` e `contracts/` são cópias geradas por `scripts/sync-shared.sh`: não edite.

## Comandos

```bash
make test        # xUnit
make lint        # dotnet format --verify-no-changes
make typecheck   # build com warnings como erro
make run         # sobe só este serviço
```

Sem o .NET SDK instalado, os alvos rodam sozinhos no container `mcr.microsoft.com/dotnet/sdk:10.0`. Para forçar: `make test TOOLCHAIN=docker`. Também há um Dev Container em [`.devcontainer/`](.devcontainer/devcontainer.json).
