# inventory-service

Catálogo de eventos, estoque por setor, reservas e emissão de ingressos do Ticket Pro, em Go.

**Dono:** Pedro.

**Estado:** F2: catálogo com seed (`GET /events`, `GET /events/{id}`), reserva atômica (`OrderCreated` → `InventoryReserved`/`InventoryRejected`), emissão de ingressos (`OrderConfirmed` → `TicketsIssued`) e devolução de estoque (`OrderCancelled`). F3: job de expiração (`PENDING` vencida → `RELEASED`, estoque devolvido, `InventoryReservationExpired`), seguro em N réplicas (`SKIP LOCKED`), e boot recusado se `RESERVATION_TTL_MS < ORDER_PAYMENT_TIMEOUT_MS + RESERVATION_SAFETY_MARGIN_MS`. F4: `OrderCreated` v1 e v2 pelo upcaster (`channel` gravado na reserva) e versão não suportada para a DLQ ([demo](../../docs/demo/versioning.md)).

## Responsabilidades

- Catálogo de eventos e setores, com seed por migration. Inclui o setor `F5` com 10 unidades, usado no cenário F5.
- **R2, anti-venda-dupla:** `UPDATE sectors SET available = available - q WHERE id = $1 AND available >= q` ([ADR-0007](../../docs/adr/0007-conditional-update-prevents-overselling.md)).
- Reservas com TTL e job de expiração.
- Emissão de ingressos quando o pedido é confirmado.
- Upcaster `OrderCreated` v1 → v2 ([RFC 0001 §5.5](../../docs/rfc/0001-message-contract-and-event-versioning.md)).

## API (via gateway)

| Endpoint | Fase | Resposta |
|---|---|---|
| `GET /health/live` | F0 ✅ | `200 {"status":"up"}` |
| `GET /health/ready` | F0 ✅ (`db`/`broker` na F1) | `200 {"status":"up","checks":{}}` |
| `GET /events` | F2 | catálogo paginado |
| `GET /events/{id}` | F2 | evento + setores com `available` |

## Eventos

| Publica (tópico `ticketpro-inventory`) | Consome (fila `ticketpro-inventory-service`) |
|---|---|
| `InventoryReserved`, `InventoryRejected`, `InventoryReservationExpired`, `TicketsIssued` | `OrderCreated` (v1 e v2), `OrderConfirmed`, `OrderCancelled` |

Os schemas estão em [`contracts/events/`](../../contracts/events).

## Dados

O banco é `inventory`, no container `inventory-db`. Tabelas: `events`, `sectors`, `reservations`, `tickets`, `outbox` e `processed_messages`.

Constraints-chave:

- `CHECK (available >= 0 AND available <= capacity)`;
- `reservations.order_id UNIQUE`;
- `tickets.code UNIQUE`.

## Por onde começar

1. Ler a seção *Primeiros passos* do [README da raiz](../../README.md).
2. Ler:
   - [arquitetura](../../docs/arquitetura.md) §3, §6 (saga) e §8;
   - [padrões](../../docs/padroes.md) §2.1 (estrutura Go) e §6–7;
   - [ADR-0007](../../docs/adr/0007-conditional-update-prevents-overselling.md);
   - [RFC 0003](../../docs/rfc/0003-failure-handling-strategy.md) §5.4 e §5.7.
3. **F1:** Tasks 3–5 do [plano F1](../../docs/superpowers/plans/2026-11-12-f1-platform.md): lib `shared/go`, banco, migrations, relay e readiness.
4. **F2:** catálogo seed, `UPDATE` condicional, reservas e `TicketsIssued` ([roadmap](../../docs/roadmap.md)). O teste obrigatório de R2: 100 goroutines × setor com 10 unidades resultam em exatamente 10 reservas.

## Variáveis de ambiente

| Variável | Obrigatória | Exemplo |
|---|---|---|
| `SERVICE_NAME` | sim | `inventory-service` |
| `PORT` | sim | `8080` |
| `LOG_LEVEL` | sim | `debug` \| `info` \| `warn` \| `error` |
| `DATABASE_URL` | sim | role `inventory_app` (API, RLS) |
| `DATABASE_WORKER_URL` | sim | role `inventory_worker` (relay, consumidores, jobs) |
| `INVENTORY_TOPIC_ARN` | sim | `arn:aws:sns:us-east-1:000000000000:ticketpro-inventory` |
| `OUTBOX_POLL_INTERVAL_MS`, `OUTBOX_BATCH_SIZE`, `OUTBOX_MAX_BACKOFF_MS`, `OUTBOX_CLAIM_LEASE_MS`, `HTTP_CLIENT_TIMEOUT_MS` | não | 200, 50, 30000, 30000, 3000 (RFC 0003 §5.1) |

O banco e o job de migration usam `db.env.example` (owner e senhas dos roles, só desenvolvimento). A pasta `internal/platform/` e a `contracts/` são cópias geradas por `scripts/sync-shared.sh`: não edite.

## Comandos

```bash
make test        # go test
make lint        # gofmt + golangci-lint (container)
make typecheck   # go vet
make run         # sobe só este serviço
```

Sem Go instalado, os alvos rodam sozinhos no container `golang:1.25`. Para forçar: `make test TOOLCHAIN=docker`. Também há um Dev Container em [`.devcontainer/`](.devcontainer/devcontainer.json).

### Expiração de reservas (F3)

| Variável | Padrão | Uso |
|---|---|---|
| `RESERVATION_TTL_MS` | 60000 | validade da reserva |
| `ORDER_PAYMENT_TIMEOUT_MS` | 15000 | mesmo valor do order-service; usado só na validação do boot |
| `RESERVATION_SAFETY_MARGIN_MS` | 30000 | margem exigida acima do deadline |
| `RESERVATION_EXPIRY_SCAN_INTERVAL_MS` | 1000 | intervalo do job |
| `RESERVATION_EXPIRY_BATCH_SIZE` | 50 | reservas por transação |
| `RESERVATION_EXPIRY_BATCH_PAUSE_MS` | 50 | pausa entre lotes cheios |

Se um `OrderConfirmed` encontrar a reserva já `RELEASED`, o inventory loga `order_confirmed_after_release` em ERROR: o cliente pagou e não recebeu ingressos.
