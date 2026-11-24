# notification-service

Registro de notificações e comprovantes de compra no S3 do Ticket Pro, em Go. É o exemplo de evento com dois ou mais consumidores: `TicketsIssued` e `OrderCancelled` também são consumidos por outros serviços.

**Donos:** Pedro e Matheus.

**Estado:** completo:
- consome `TicketsIssued` (grava `receipts/{orderId}.json` no S3 e registra a notificação) e `OrderCancelled` (registra a notificação);
- readiness com `db` e `broker`;
- tracing HTTP, de banco e de mensagens.

Design completo: [RFC 0011](../../docs/rfc/0011-design-do-notification-service-em-go.md). Plano: [notification-service](../../docs/superpowers/plans/2026-11-19-notification-service.md).

## O que "notificar" significa

Não há canal externo (e-mail, SMS ou push). Notificar é **registrar** em `notifications` e, no caso de `TicketsIssued`, **gravar o comprovante** no S3 (RFC 0011, Decisão 2). O comprovante só existe depois que a saga termina, então a prova ponta a ponta depende do order e do inventory em Java emitirem os eventos.

## Responsabilidades

- Consumir `TicketsIssued` e gravar o comprovante em `s3://ticketpro-receipts/receipts/{orderId}.json`. A chave é determinística, então reescrever é idempotente ([ADR-0006](../../docs/adr/0006-idempotent-consumers-processed-messages.md)).
- Consumir `OrderCancelled` e registrar a notificação de cancelamento.
- Não tem API HTTP além dos health checks.

## API

| Endpoint | Fase | Resposta |
|---|---|---|
| `GET /health/live` | ✅ | `200 {"status":"up"}` |
| `GET /health/ready` | ✅ | `200 {"status":"up","checks":{"db":"up","broker":"up"}}`; banco fora → `503`; broker fora → `checks.broker` = `degraded`, ainda 200 (RFC 0003, Decisão 8) |

## Eventos

| Publica | Consome (fila `ticketpro-notification-service`) |
|---|---|
| — | `TicketsIssued`, `OrderCancelled` |

## Dados

O banco é `notifications`, no container `notifications-db`. Tabelas: `notifications` (`(order_id, kind) UNIQUE`) e `processed_messages`. Não tem outbox, porque o serviço não publica.

## Código

```
cmd/notification-service/main.go   wiring; subcomando `healthcheck`
internal/
├── app/        config (env), servidor HTTP (health), workers, shutdown
├── receipts/   TicketsIssued/OrderCancelled → comprovante no S3 + tabela notifications
├── testdb/     Postgres de teste com as migrations reais
└── platform/   cópia de shared/go: não edite
```

Escolha do dono ([ADR-0023](../../docs/adr/0023-internal-architecture-chosen-per-service.md)): layout padrão de Go, com `cmd/` + `internal/` e um pacote por responsabilidade, sem nível `modules/` nem pacotes por camada, como no gateway e no autoscaler.

## Por onde começar

1. Ler a seção *Primeiros passos* do [README da raiz](../../README.md).
2. Ler:
   - [arquitetura](../../docs/arquitetura.md) §3, §4 e §8;
   - [padrões](../../docs/padroes.md) §2.1 e §7;
   - [ADR-0006](../../docs/adr/0006-idempotent-consumers-processed-messages.md);
   - [RFC 0003](../../docs/rfc/0003-failure-handling-strategy.md) §5.4.
3. Design e plano: [RFC 0011](../../docs/rfc/0011-design-do-notification-service-em-go.md) e [plano](../../docs/superpowers/plans/2026-11-19-notification-service.md).
4. Testes: `make test` (unidade) e `make test-integration` (pacote `receipts` contra o MiniStack e o Postgres).
5. Ao vivo, com a stack no ar: `scripts/notification-check.sh` (na raiz) publica `TicketsIssued` e `OrderCancelled` ×5 e confere um comprovante no S3 e uma linha em `notifications`.

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

OpenTelemetry ([ADR-0012](../../docs/adr/0012-opentelemetry-tracing.md)). `OTEL_EXPORTER_OTLP_ENDPOINT` (padrão `otel-collector:4317`) envia os spans ao collector, e de lá para o Jaeger (`http://localhost:16686`). Sem a variável, nada é exportado. `OTEL_SERVICE_NAME` é opcional; o padrão é `SERVICE_NAME`.
- **HTTP:** um span por requisição (`otelhttp`), exceto `/health/*`.
- **Banco:** spans das queries (`otelpgx`).
- **Mensagens:** span `consumer` por mensagem, no trace do producer e com link para ele. Span `producer` por publicação do outbox, que propaga o `traceparent`.
