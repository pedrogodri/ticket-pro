# order-service

API assíncrona de pedidos e orquestrador da saga de compra do Ticket Pro, em Java 21 (Spring Boot).

**Dono:** Matheus.

**Estado:** a implementação TypeScript foi removida ([ADR-0021](../../docs/adr/0021-order-and-inventory-in-java.md)) e será refeita em Java. Esta pasta guarda o que ela deve cumprir: migrations, `compose.yaml`, `contracts/` e este README. O código antigo está no histórico do git, no commit `65d538c` (`git show 65d538c:services/order-service/src/...`), como referência de comportamento. O comportamento esperado era: API de pedidos (`POST /orders` 202 + `Idempotency-Key`, `GET /orders/{id}`, `GET /orders`) e saga (reserva → pagamento → confirmação; estoque rejeitado ou pagamento recusado cancelam). Deadline scanner e estorno tardio chegam na F3.

## Responsabilidades

- **R3:** `POST /orders` responde `202` + `Location`, com `Idempotency-Key` obrigatório. O cliente consulta `GET /orders/{id}`.
- Máquina de estados da saga e compensações ([arquitetura §6](../../docs/arquitetura.md), [RFC 0003 §5.7](../../docs/rfc/0003-failure-handling-strategy.md)).
- Deadline scanner de pagamento e estorno de aprovação tardia (F7).
- `OrderCreated` v2 controlado por `ORDER_CREATED_VERSION` (bônus de versionamento).
- **BOLA:** pedido de outro usuário → `404`, com RLS por `app.user_id`.

## API (via gateway)

| Endpoint            | Fase                        | Resposta                          |
| ------------------- | --------------------------- | --------------------------------- |
| `GET /health/live`  | F0 ✅                       | `200 {"status":"up"}`             |
| `GET /health/ready` | F0 ✅ (`db`/`broker` na F1) | `200 {"status":"up","checks":{}}` |
| `POST /orders`      | F2                          | `202` + `Location`                |
| `GET /orders/{id}`  | F2                          | pedido com status                 |
| `GET /orders`       | F2                          | pedidos do usuário, paginados     |

## Eventos

| Publica (tópico `ticketpro-orders`)                                                               | Consome (fila `ticketpro-order-service`)                                                                                       |
| ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `OrderCreated` (v1/v2), `PaymentRequested`, `OrderConfirmed`, `OrderCancelled`, `RefundRequested` | `InventoryReserved`, `InventoryRejected`, `InventoryReservationExpired`, `PaymentApproved`, `PaymentFailed`, `PaymentRefunded` |

## Dados

O banco é `orders`, no container `orders-db`. Tabelas: `orders`, `outbox` e `processed_messages`.

Constraints-chave:

- `(user_id, idempotency_key) UNIQUE`;
- `CHECK status IN (...)`;
- `CHECK quantity BETWEEN 1 AND 6`.

## Por onde começar

1. Ler a seção _Primeiros passos_ do [README da raiz](../../README.md).
2. Ler:
   - [arquitetura](../../docs/arquitetura.md) §6 (saga);
   - [RFC 0003](../../docs/rfc/0003-failure-handling-strategy.md) inteira;
   - [RFC 0001 §5.5](../../docs/rfc/0001-message-contract-and-event-versioning.md);
   - [padrões](../../docs/padroes.md) §2.2 (estrutura Java), §4 e §6.
3. **Plataforma:** o pacote `platform/` em Java (envelope, outbox, idempotência, mensageria, logging), seguindo o _Platform contract_ do [plano F1](../../docs/superpowers/plans/2026-11-12-f1-platform.md) e os testes P01–P15; banco, migrations, relay e readiness. O `auth-service` é o exemplo Java.
4. **Saga:** `POST /orders`, `GET /orders/{id}`, a saga e o `scripts/happy-path.sh` ([roadmap](../../docs/roadmap.md)).

## Variáveis de ambiente

| Variável                                                                                                                   | Obrigatória | Exemplo                                                    |
| -------------------------------------------------------------------------------------------------------------------------- | ----------- | ---------------------------------------------------------- |
| `SERVICE_NAME`                                                                                                             | sim         | `order-service`                                            |
| `PORT`                                                                                                                     | sim         | `8080`                                                     |
| `LOG_LEVEL`                                                                                                                | sim         | `debug` \| `info` \| `warn` \| `error`                     |
| `DATABASE_URL`                                                                                                             | sim         | `postgres://orders_app:…@orders-db:5432/orders` (API, RLS) |
| `DATABASE_WORKER_URL`                                                                                                      | sim         | role `orders_worker` (relay, consumidores, jobs)           |
| `ORDERS_TOPIC_ARN`                                                                                                         | sim         | `arn:aws:sns:us-east-1:000000000000:ticketpro-orders`      |
| `AWS_REGION`                                                                                                               | sim         | `us-east-1` (`AWS_ENDPOINT_URL` aponta para o MiniStack)   |
| `OUTBOX_POLL_INTERVAL_MS`, `OUTBOX_BATCH_SIZE`, `OUTBOX_MAX_BACKOFF_MS`, `OUTBOX_CLAIM_LEASE_MS`, `HTTP_CLIENT_TIMEOUT_MS` | não         | 200, 50, 30000, 30000, 3000 (RFC 0003 §5.1)                |

O banco e o job de migration usam `db.env.example` (owner e senhas dos roles, só desenvolvimento). `contracts/` é cópia gerada por `scripts/sync-shared.sh`: não edite.

## Comandos

A fazer: `Makefile`, `Dockerfile` e `.devcontainer/` em Java, com os alvos de [padrões §1.2](../../docs/padroes.md) (`test`, `test-integration`, `lint`, `typecheck`, `run`), iguais aos do `auth-service`. Até lá, o `compose.yaml` desta pasta não builda.
