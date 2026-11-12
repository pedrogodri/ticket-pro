# notification-service

Registro de notificações e comprovantes de compra no S3 do Ticket Pro, em Go. É o exemplo de evento com dois ou mais consumidores: `TicketsIssued` e `OrderCancelled` também são consumidos por outros serviços.

**Donos:** Pedro e Matheus.

**Estado:** esqueleto. Hoje só tem config validada, logs JSON e health checks. O banco e o consumidor idempotente chegam na F1/F2.

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

## Comandos

```bash
make test        # go test
make lint        # gofmt + golangci-lint (container)
make typecheck   # go vet
make run         # sobe só este serviço
```

Sem Go instalado, os alvos rodam sozinhos no container `golang:1.25`. Para forçar: `make test TOOLCHAIN=docker`. Também há um Dev Container em [`.devcontainer/`](.devcontainer/devcontainer.json).
