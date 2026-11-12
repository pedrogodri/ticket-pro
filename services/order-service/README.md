# order-service

API assíncrona de pedidos e orquestrador da saga de compra do Ticket Pro, em TypeScript com Node 22.

**Dono:** Matheus.

**Estado:** esqueleto. Hoje só tem config validada, logs JSON e health checks. A plataforma TS, o banco e o relay chegam na F1. A API e a saga chegam na F2.

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
   - [padrões](../../docs/padroes.md) §2.4 (estrutura TS), §4 e §6.
3. **F1:** Tasks 13–14 do [plano F1](../../docs/superpowers/plans/2026-11-12-f1-platform.md):
   - a camada `src/platform/` em TS, seguindo o _Platform contract_ e os testes P01–P15;
   - banco, migrations, relay e readiness.
4. **F2:** `POST /orders`, `GET /orders/{id}`, a saga e o `scripts/happy-path.sh` ([roadmap](../../docs/roadmap.md)).

## Variáveis de ambiente

| Variável       | Obrigatória | Exemplo                                |
| -------------- | ----------- | -------------------------------------- |
| `SERVICE_NAME` | sim         | `order-service`                        |
| `PORT`         | sim         | `8080`                                 |
| `LOG_LEVEL`    | sim         | `debug` \| `info` \| `warn` \| `error` |

## Comandos

```bash
make test        # Vitest
make lint        # ESLint (typescript-eslint strict, sem any) + Prettier
make typecheck   # tsc --noEmit (strict)
make run         # sobe só este serviço
```

Sem Node instalado, os alvos rodam sozinhos no container `node:22`, com `node_modules` num volume, fora do checkout. Para forçar: `make test TOOLCHAIN=docker`. Também há um Dev Container em [`.devcontainer/`](.devcontainer/devcontainer.json).
