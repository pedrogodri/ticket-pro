# payment-service

Pagamentos do Ticket Pro, em C# com .NET 10. Simula um provedor de pagamento lento e instável ([RFC 0003 §5.6](../../docs/rfc/0003-failure-handling-strategy.md)).

**Dono:** Gabriel.

**Estado:** F3 completo: consumidor de `PaymentRequested` (grava `PENDING` e dá ack), worker com lease (`SKIP LOCKED`) e provedor simulado com latência, jitter, taxa de falha e timeout duro, publicando `PaymentApproved`/`PaymentFailed` pelo outbox, e estorno `RefundRequested` → `PaymentRefunded`.

**Design:** [RFC 0009](../../docs/rfc/0009-design-do-payment-service-em-csharp.md) e [plano](../../docs/superpowers/plans/2026-11-19-payment-service.md). **Para apresentar:** [docs/demo/payment-service.md](../../docs/demo/payment-service.md) explica o serviço, as garantias, o roteiro de demo e as perguntas prováveis da banca.

## Responsabilidades

- Consumir `PaymentRequested` e gravar `payments` (`PENDING`), dando ack logo depois. A cobrança **não** acontece no consumo.
- Worker com lease (`FOR UPDATE SKIP LOCKED`) que chama o provedor simulado. O timeout é duro e não há retry de cobrança.
- **R5:** latência e taxa de falha configuráveis por env (`PAYMENT_LATENCY_MS`, `PAYMENT_FAILURE_RATE`…).
- Estorno: `RefundRequested` → `PaymentRefunded`.

## API

| Endpoint | Fase | Resposta |
|---|---|---|
| `GET /health/live` | F0 ✅ | `200 {"status":"up"}` |
| `GET /health/ready` | F1 ✅ | `200 {"status":"up","checks":{"db":"up","broker":"up"}}`; broker fora → `"broker":"degraded"` (continua 200); banco fora → `503` |

O serviço não tem API de negócio, só eventos.

## Eventos

| Publica (tópico `ticketpro-payments`) | Consome (fila `ticketpro-payment-service`) |
|---|---|
| `PaymentApproved`, `PaymentFailed`, `PaymentRefunded` | `PaymentRequested`, `RefundRequested` |

## Dados

O banco é `payments`, no container `payments-db`. Tabelas: `payments` (`order_id UNIQUE`; `correlation_id` desde a V0006; `traceparent` desde a V0007), `refunds` (`payment_id UNIQUE`), `provider_charges` (memória do provedor simulado, idempotência por `paymentId`), `outbox` e `processed_messages`.

## Código

| Arquivo | Papel |
|---|---|
| `src/PaymentService/Program.cs` | liga config, logs, bancos, clientes AWS, consumidor, relay, worker e health |
| `App/AppConfig.cs` | lê e valida todas as envs no boot |
| `App/HealthEndpoints.cs` | `/health/live` e `/health/ready` |
| `Modules/Payments/Consumers/` | `PaymentRequestedHandler` (grava `PENDING`), `RefundRequestedHandler` (estorno) |
| `Modules/Payments/Workers/PaymentWorker.cs` | lease + cobrança + resultado no outbox |
| `Modules/Payments/Provider/` | `IPaymentProvider` e o provedor simulado |
| `tests/PaymentService.Tests/` | `Unit/` (config, health, simulação, contratos) e `Integration/` (fluxo completo com Postgres real) |

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
| `AWS_REGION`, `QUEUE_URL`, `CONTRACTS_DIR` | sim | `us-east-1`, fila `ticketpro-payment-service`, `/contracts` |
| `AWS_ENDPOINT_URL` | não | `http://ministack:4566` (sem ele, AWS real) |
| `CONSUMER_CONCURRENCY`, `SQS_WAIT_TIME_S` | não | 4 (também é o lote do worker), 10 (≤ 20) |
| `PAYMENT_LATENCY_MS` | não | 500: latência média do provedor simulado (R5) |
| `PAYMENT_LATENCY_JITTER_MS` | não | 300: variação ± em torno da latência |
| `PAYMENT_FAILURE_RATE` | não | 0.1: probabilidade de `DECLINED`, de 0.0 a 1.0 (o F1 usa 1) |
| `PAYMENT_PROVIDER_TIMEOUT_MS` | não | 8000: timeout duro → `FAILED(PROVIDER_TIMEOUT)`, sem retry |
| `PAYMENT_WORKER_LEASE_MS` | não | 30000: **precisa ser maior** que `PAYMENT_PROVIDER_TIMEOUT_MS`, senão o serviço não sobe |
| `PAYMENT_WORKER_POLL_MS` | não | 200: intervalo do worker quando não há `PENDING` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | não | `http://otel-collector:4317` (com esquema): liga o tracing; sem ela, nada é exportado |

Toda variável é validada no boot: valor inválido impede a subida com `invalid configuration: <VARIÁVEL>: ...`.

O banco e o job de migration usam `db.env.example` (owner e senhas dos roles, só desenvolvimento). `src/PaymentService/Platform/` e `contracts/` são cópias geradas por `scripts/sync-shared.sh`: não edite.

## Observabilidade

Com `OTEL_EXPORTER_OTLP_ENDPOINT` definida, o serviço exporta traces por OTLP gRPC para o collector ([ADR-0012](../../docs/adr/0012-opentelemetry-tracing.md)):

- `process PaymentRequested` / `process RefundRequested` (consumer): continuam o trace do order, com link para o produtor. Vêm do `Platform/`.
- `charge payment` (internal): o worker cobra depois, fora da mensagem, mas continua o mesmo trace pelo `payments.traceparent` (V0007).
- `publish PaymentApproved|PaymentFailed|PaymentRefunded` (producer): no relay; o `traceparent` da mensagem passa a ser o deste span.
- As queries Npgsql dentro desses spans.

O `QuietPollingSampler` descarta spans `Client` sem pai, ou seja, as queries de polling do worker e do relay, para o Jaeger não encher.

## Comandos

```bash
make test               # xUnit, unitários
make test-integration   # Testcontainers: Postgres real com as migrations deste serviço
make lint        # dotnet format --verify-no-changes
make typecheck   # build com warnings como erro
make run         # sobe só este serviço
```

Com a stack no ar, `scripts/payment-check.sh` (na raiz) verifica o serviço sozinho, sem order nem inventory: publica `PaymentRequested`/`RefundRequested` e confere aprovação, idempotência, estorno único, `DECLINED` e timeout duro.

Sem o .NET SDK instalado, os alvos rodam sozinhos no container `mcr.microsoft.com/dotnet/sdk:10.0`. Para forçar: `make test TOOLCHAIN=docker`. Também há um Dev Container em [`.devcontainer/`](.devcontainer/devcontainer.json).
