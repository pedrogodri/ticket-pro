# notification-service

Registro de notificações e comprovantes de compra no S3 do Ticket Pro, em Go. É o exemplo de evento com dois ou mais consumidores: `TicketsIssued` e `OrderCancelled` também são consumidos por outros serviços.

**Donos:** Pedro e Matheus.

**Estado:** completo:
- consome `TicketsIssued` (grava `receipts/{orderId}.json` no S3 e registra a notificação) e `OrderCancelled` (registra a notificação);
- readiness com `db` e `broker`;
- tracing HTTP, de banco e de mensagens.

## Responsabilidades

- Consumir `TicketsIssued` e gravar o comprovante em `s3://ticketpro-receipts/receipts/{orderId}.json`. A chave é determinística, então reescrever é idempotente ([ADR-0006](../../docs/adr/0006-idempotent-consumers-processed-messages.md)).
- Consumir `OrderCancelled` e registrar a notificação de cancelamento.
- Não tem API HTTP além dos health checks.

## API

| Endpoint | Fase | Resposta |
|---|---|---|
| `GET /health/live` | F0 ✅ | `200 {"status":"up"}` |
| `GET /health/ready` | F0 ✅ (`db` na F1) | `200 {"status":"up","checks":{}}` |

## Eventos

| Publica | Consome (fila `ticketpro-notification-service`) |
|---|---|
| — | `TicketsIssued`, `OrderCancelled` |

## Dados

O banco é `notifications`, no container `notifications-db`. Tabelas: `notifications` (`(order_id, kind) UNIQUE`) e `processed_messages`. Não tem outbox, porque o serviço não publica.

## Por onde começar

1. Ler a seção *Primeiros passos* do [README da raiz](../../README.md).
2. Ler:
   - [arquitetura](../../docs/arquitetura.md) §3, §4 e §8;
   - [padrões](../../docs/padroes.md) §2.1 e §7;
   - [ADR-0006](../../docs/adr/0006-idempotent-consumers-processed-messages.md);
   - [RFC 0003](../../docs/rfc/0003-failure-handling-strategy.md) §5.4.
3. **F1:** Task 6 do [plano F1](../../docs/superpowers/plans/2026-11-12-f1-platform.md) (banco + readiness). Depende das Tasks 3–4, a lib `shared/go`.
4. **F2:** consumir `TicketsIssued`/`OrderCancelled` e gravar o comprovante no S3 ([roadmap](../../docs/roadmap.md)).

## Variáveis de ambiente

| Variável | Obrigatória | Exemplo |
|---|---|---|
| `SERVICE_NAME` | sim | `notification-service` |
| `PORT` | sim | `8080` |
| `LOG_LEVEL` | sim | `debug` \| `info` \| `warn` \| `error` |
| `DATABASE_URL` | sim | role `notifications_app` (RLS) |
| `DATABASE_WORKER_URL` | sim | role `notifications_worker` (consumidores, jobs) |
| `HTTP_CLIENT_TIMEOUT_MS` | não | 3000 |
| `QUEUE_URL`, `CONTRACTS_DIR`, `RECEIPTS_BUCKET` | sim | fila própria, contratos vendorizados, `ticketpro-receipts` |
| `CONSUMER_CONCURRENCY`, `SQS_WAIT_TIME_S` | não | 4, 10 |

O banco e o job de migration usam `db.env.example` (owner e senhas dos roles, só desenvolvimento). A pasta `internal/platform/` e a `contracts/` são cópias geradas por `scripts/sync-shared.sh`: não edite.

## Comandos

```bash
make test        # go test
make lint        # gofmt + golangci-lint (container)
make typecheck   # go vet
make run         # sobe só este serviço
```

Sem Go instalado, os alvos rodam sozinhos no container `golang:1.25`. Para forçar: `make test TOOLCHAIN=docker`. Também há um Dev Container em [`.devcontainer/`](.devcontainer/devcontainer.json).

## Tracing

OpenTelemetry ([ADR-0012](../../docs/adr/0012-opentelemetry-collector-jaeger.md)). `OTEL_EXPORTER_OTLP_ENDPOINT` (padrão `otel-collector:4317`) envia os spans ao collector, e de lá para o Jaeger (`http://localhost:16686`). Sem a variável, nada é exportado. `OTEL_SERVICE_NAME` é opcional; o padrão é `SERVICE_NAME`.
- **HTTP:** um span por requisição (`otelhttp`), exceto `/health/*`.
- **Banco:** spans das queries (`otelpgx`).
- **Mensagens:** span `consumer` por mensagem, no trace do producer e com link para ele. Span `producer` por publicação do outbox, que propaga o `traceparent`.
