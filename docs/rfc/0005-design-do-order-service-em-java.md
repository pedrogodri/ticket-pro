# RFC 0005 — Design do order-service em Java

- **Status:** 📝 Draft (design fechado — T09–T11 do [plano](../superpowers/plans/2026-11-19-java-order-inventory.md) implementadas em `services/order-service`; falta o ensaio ponta a ponta com o inventory, T12)
- **Scope:** O módulo `orders` do `order-service` (Java 21, Spring Boot): API assíncrona (`POST /orders` → 202 com `Idempotency-Key`, `GET /orders/{id}`, `GET /orders`), a máquina de estados da saga `PENDING → AWAITING_PAYMENT → CONFIRMED | CANCELLED` com `UPDATE` condicional, o deadline scanner e o estorno de aprovação tardia. Usa a camada [RFC 0004](0004-camada-platform-em-java-para-order-e-inventory.md) e não a redefine. **Sem mudança de contrato e sem migration nova**; os pontos que talvez exijam uma (Decisão 5) ficam como `OPEN`.
- **Related:** [ADR-0003](../adr/0003-orchestrated-saga-in-order-service.md), [ADR-0006](../adr/0006-idempotent-consumers-processed-messages.md), [ADR-0021](../adr/0021-order-and-inventory-in-java.md), [RFC 0001 §5.5](0001-message-contract-and-event-versioning.md), [RFC 0002 §5.6](0002-authentication-and-authorization.md), [RFC 0003 §5.1, §5.7](0003-failure-handling-strategy.md), [arquitetura §6](../arquitetura.md), [`services/order-service/migrations/`](../../services/order-service/migrations/V0004__create_orders.sql), [`contracts/events/`](../../contracts/asyncapi.yaml). Comportamento de referência (TypeScript removido): `git show 65d538c:services/order-service/src/modules/orders/...`.

---

## 1. Summary

O order-service é dono do estado do pedido e orquestra a compra. A API grava `orders(PENDING)` + `OrderCreated` no outbox numa única transação e responde `202`. Seis consumidores (`InventoryReserved`, `InventoryRejected`, `InventoryReservationExpired`, `PaymentApproved`, `PaymentFailed`, `PaymentRefunded`) movem o pedido por `UPDATE … WHERE id = ? AND status = <esperado> RETURNING …`; zero linhas significa evento atrasado, duplicado ou fora de ordem (`StaleEventException`, sem efeito). Um deadline scanner cancela `AWAITING_PAYMENT` vencido, e uma aprovação que chega depois do cancelamento vira `RefundRequested`.

## 2. Motivation

Sem o order, `POST /orders` não existe: `happy-path.sh` e F1, F5, F6, F7, F8 dependem dele (o ensaio atual falha em todos). O código TypeScript que cumpria esse papel foi removido (ADR-0021) e a regra, que é sutil (corrida entre `PaymentApproved` e deadline, estorno idempotente), precisa ser reescrita com a mesma semântica. Este documento fixa essa semântica para que Matheus implemente sem reler a história do git.

## 3. Current state

Verificado em 19/11:

- Código: `OrderApplication`, `HealthController`, `application.yml` (Jackson rejeita campos desconhecidos; `server.shutdown: graceful`).
- Migrations prontas e aplicadas pelo `order-service-migrate`: V0001 (roles), V0002 (`outbox`), V0003 (`processed_messages`), V0004 (`orders`), V0005 (`refund_status`), V0006 (índice do deadline). V0004/V0006 criam o **mesmo** índice parcial com nomes diferentes (`orders_awaiting_deadline_idx` e `orders_awaiting_payment_deadline_idx`); inofensivo (índice duplicado), mas não se edita migration aplicada.
- `orders`: `status` ∈ {PENDING, AWAITING_PAYMENT, CONFIRMED, CANCELLED}; `cancel_reason` ∈ {OUT_OF_STOCK, PAYMENT_FAILED, PAYMENT_TIMEOUT, RESERVATION_EXPIRED}; `refund_status` ∈ {REQUESTED, REFUNDED}; `UNIQUE (user_id, idempotency_key)`; `CHECK quantity BETWEEN 1 AND 6`; RLS `ENABLE` + `FORCE` com política `orders_owner` em `app.user_id`. **Não há** colunas `correlation_id` nem `payment_id`.
- `.env.example` já traz `ORDERS_TOPIC_ARN`, `QUEUE_URL`, `ORDER_PAYMENT_TIMEOUT_MS=15000`, `ORDER_DEADLINE_SCAN_*`, `RESERVATION_*`, `ORDER_MAX_QUANTITY=6`, `ORDER_CREATED_VERSION=2`; o `compose.yaml` sobe 2 réplicas e passa `ORDER_CREATED_VERSION` do ambiente.
- Contratos (`contracts/events/…`): o order **publica** `OrderCreated` v1/v2, `PaymentRequested`, `OrderConfirmed`, `OrderCancelled`, `RefundRequested` (tópico `ticketpro-orders`) e **consome** `InventoryReserved`, `InventoryRejected`, `InventoryReservationExpired` (inventory) e `PaymentApproved`, `PaymentFailed`, `PaymentRefunded` (payment).
- **Divergência de contrato (registrada, não decidida aqui):** `InventoryRejected.reason` ∈ {`OUT_OF_STOCK`, `SECTOR_NOT_FOUND`, `INVALID_QUANTITY`}, mas `OrderCancelled.reason` e `orders.cancel_reason` só aceitam quatro valores, sem equivalente para os dois últimos. O código antigo cancelava tudo como `OUT_OF_STOCK`.

## 4. Goals / Non-goals

**Goals**

- R3: `POST /orders` responde `202` + `Location` sem esperar a saga; `GET /orders/{id}` mostra a evolução.
- Saga correta sob duplicata, reordenação e corrida (R1; F3, F7).
- BOLA: pedido de outro usuário → `404` (RFC 0002 §5.6), com RLS como segunda barreira.
- Todo prazo e limite vem de env (padrões §8).

**Non-goals**

- Consultar catálogo/estoque no inventory (dado alheio só chega por evento; ADR-0004). Setor inexistente é descoberto pelo `InventoryRejected`.
- Pagamento, estoque, ingressos (outros serviços).
- Cancelamento pelo usuário (fora do enunciado).

## 5. Design

### 5.1 Estrutura (padrões §2.2)

```
br.furb.ticketpro.order
├── OrderApplication
├── platform/                         # RFC 0004
└── modules/orders/
    ├── web/            OrderController, CreateOrderRequest, OrderResponse, CursorCodec
    ├── application/    CreateOrder, GetOrder, ListOrders, SagaHandlers (um MessageHandler por evento), DeadlineScanner (PeriodicJob)
    ├── domain/         OrderStatus, CancelReason, RefundStatus, OrderView, Transitions (SQL como constantes)
    └── infrastructure/ OrderRepository (JDBC)
```

Domain não importa Spring nem JDBC. Os handlers de mensagem só traduzem o payload e chamam `Transitions`.

### 5.2 API (via gateway)

| Endpoint | Sucesso | Erros |
|---|---|---|
| `POST /orders` `{eventId, sectorId, quantity}` + `Idempotency-Key` (UUID) | `202` + `Location: /orders/{id}` + `{orderId, status:"PENDING"}`; chave repetida → `200` + pedido existente | `400 VALIDATION_ERROR` (campo desconhecido, UUID inválido, `quantity` fora de 1..`ORDER_MAX_QUANTITY`, header ausente), `401` sem `X-User-Id` |
| `GET /orders/{id}` | `200` `OrderResponse` | `404 NOT_FOUND` (inexistente, de outro usuário ou id inválido) |
| `GET /orders?limit=&cursor=` | `200 {items, nextCursor}` (limit ≤ 50, padrão 20, ordem `created_at DESC, id DESC`) | `400` cursor/limit inválido |

`OrderResponse`: `orderId, status, eventId, sectorId, quantity, totalCents|null, currency|null, cancelReason|null, createdAt`. É um DTO explícito, nunca a linha do banco (OWASP API3). Identidade só de `X-User-Id`; `X-Correlation-Id` do gateway, ou um novo UUID em chamada direta. Erros em `application/problem+json` PT-BR (padrões §4.1).

### 5.3 Criação (R3, idempotência)

Uma TX no pool **app** (`UserScopedTransaction`, RFC 0004 §5.5):

1. `SELECT … WHERE user_id = ? AND idempotency_key = ?` → existe: devolve `200` com o pedido.
2. `INSERT INTO orders (…, status) VALUES (…, 'PENDING')`.
3. `OutboxWriter.enqueue(OrderCreated, v = ORDER_CREATED_VERSION)`; em v2, `channel = "API"` (todo pedido entra pela API).
4. Commit → `202`.

Duas requisições simultâneas com a mesma chave: a perdedora recebe `23505` em `(user_id, idempotency_key)`, faz rollback, relê o pedido vencedor e responde `200`. O order **não** valida `eventId`/`sectorId` (ver Non-goals).

### 5.4 Saga: um `UPDATE` condicional por evento (R1)

Todos os handlers rodam no pool **worker**, dentro da TX do consumidor (RFC 0004 §5.3), e lançam `StaleEventException` se o `RETURNING` vier vazio.

| Evento (v1) | Guarda (`WHERE`) | Efeito | Publica (outbox, mesma TX) |
|---|---|---|---|
| `InventoryReserved` | `id = ? AND status = 'PENDING'` | `AWAITING_PAYMENT`, `total_cents`, `currency` do evento, `payment_deadline_at = now() + ORDER_PAYMENT_TIMEOUT_MS` | `PaymentRequested {orderId, userId, amountCents, currency}` |
| `InventoryRejected` | `status = 'PENDING'` | `CANCELLED(OUT_OF_STOCK)` (Decisão 4) | `OrderCancelled` |
| `PaymentApproved` | `status = 'AWAITING_PAYMENT' AND payment_deadline_at >= now()` | `CONFIRMED` | `OrderConfirmed {…, confirmedAt}` |
| `PaymentApproved` (tardia) | ver 5.6 | cancela/estorna | `RefundRequested` (+ `OrderCancelled`) |
| `PaymentFailed` | `status = 'AWAITING_PAYMENT'` | `CANCELLED(PAYMENT_FAILED)` | `OrderCancelled` |
| `InventoryReservationExpired` | `status IN ('PENDING','AWAITING_PAYMENT')` | `CANCELLED(RESERVATION_EXPIRED)` | `OrderCancelled` |
| `PaymentRefunded` | `refund_status = 'REQUESTED'` | `refund_status = 'REFUNDED'` | — |

```java
// exemplo (InventoryReserved): a mesma instrução decide e aplica; sem SELECT antes.
static final String TO_AWAITING_PAYMENT = """
    UPDATE orders SET status = 'AWAITING_PAYMENT', total_cents = ?, currency = ?,
           payment_deadline_at = now() + (? * interval '1 millisecond'), updated_at = now()
     WHERE id = ? AND status = 'PENDING'
 RETURNING id, user_id, event_id, sector_id, quantity, total_cents, currency""";
```

`now()` é o do banco (o prazo nunca depende do relógio da JVM). O payload é lido com validação mínima por tipo e qualquer campo inválido vira `InvalidMessageException` (venenosa → DLQ), mesmo o schema já tendo passado no `ContractValidator`.

### 5.5 Deadline scanner (F1, F7)

`DeadlineScanner implements PeriodicJob` (RFC 0004 §5.7), a cada `ORDER_DEADLINE_SCAN_INTERVAL_MS`, lote `ORDER_DEADLINE_SCAN_BATCH_SIZE`:

```sql
WITH due AS (SELECT id FROM orders
              WHERE status = 'AWAITING_PAYMENT' AND payment_deadline_at < now()
              ORDER BY payment_deadline_at LIMIT ? FOR UPDATE SKIP LOCKED)
UPDATE orders o SET status = 'CANCELLED', cancel_reason = 'PAYMENT_TIMEOUT', updated_at = now()
  FROM due WHERE o.id = due.id RETURNING o.id, o.user_id
```

e um `OrderCancelled(PAYMENT_TIMEOUT)` por linha, na mesma TX. A corrida com `PaymentApproved` se resolve no banco: se a aprovação confirma primeiro, o scanner não enxerga a linha; se o scanner vence, a aprovação cai em 5.6.

### 5.6 Aprovação tardia e estorno (F7)

`PaymentApproved` que não confirma (zero linhas na guarda de 5.4):

1. `UPDATE … SET status='CANCELLED', cancel_reason='PAYMENT_TIMEOUT', refund_status='REQUESTED' WHERE id=? AND status='AWAITING_PAYMENT' AND payment_deadline_at < now() RETURNING …` → cancelou agora: enfileira `OrderCancelled` **e** `RefundRequested`.
2. Senão: `UPDATE … SET refund_status='REQUESTED' WHERE id=? AND status='CANCELLED' AND refund_status IS NULL` → enfileira só `RefundRequested`. `refund_status IS NULL` garante **um** estorno mesmo com aprovação duplicada.
3. Senão (já `CONFIRMED`, ou estorno já pedido, ou pedido `PENDING`): `StaleEventException`.

`RefundRequested {orderId, paymentId, amountCents, currency, reason:"LATE_APPROVAL"}` usa o `paymentId` e o valor **do evento** (o order não guarda `payment_id`). `PaymentRefunded` fecha com `refund_status = 'REFUNDED'`. Loga `late_payment_refund`.

### 5.7 Configuração e boot (RFC 0004 §5.8)

Obrigatórias: `ORDERS_TOPIC_ARN`, `QUEUE_URL`, `CONTRACTS_DIR`, `DATABASE_*`. Com padrão: `ORDER_PAYMENT_TIMEOUT_MS` 15000, `ORDER_DEADLINE_SCAN_INTERVAL_MS` 1000, `ORDER_DEADLINE_SCAN_BATCH_SIZE` 50, `ORDER_MAX_QUANTITY` 6 (**falha no boot se > 6**, por causa do `CHECK` do banco), `ORDER_CREATED_VERSION` 2 (1 ou 2). O boot recusa `RESERVATION_TTL_MS < ORDER_PAYMENT_TIMEOUT_MS + RESERVATION_SAFETY_MARGIN_MS` e nomeia as variáveis.

### 5.8 Cenários

| Cenário | O que o order faz |
|---|---|
| `happy-path.sh` | 202 + `Location`; `GET` chega a `CONFIRMED`; mesma `Idempotency-Key` → `200` e o mesmo `orderId` |
| F1 | `PaymentFailed` → `CANCELLED(PAYMENT_FAILED)` + `OrderCancelled` |
| F3 | mesmo `messageId` ×5 nos consumidores: um efeito (RFC 0004 §5.3) |
| F5 | 100 pedidos: 90 `InventoryRejected` → `CANCELLED(OUT_OF_STOCK)` |
| F6 | `POST /orders` responde 202 com o broker fora; o outbox acumula; o relay drena |
| F7 | scanner cancela em `PAYMENT_TIMEOUT`; aprovação tardia → `RefundRequested`; `PaymentRefunded` → `REFUNDED` |
| F8 | 2+ réplicas; scanner e consumidores sem duplicidade (`SKIP LOCKED`, `processed_messages`) |

### Alternatives considered

- **Ler o pedido e decidir em Java antes de gravar.** Abre janela de corrida entre réplicas; o `UPDATE` condicional elimina a janela e é o que o ADR-0003 manda.
- **Validar `sectorId` no order consultando o inventory por HTTP.** Cria acoplamento síncrono e viola "dado alheio só por evento".
- **`@Version` (lock otimista) no pedido.** Gera retries sob F5; o `status` já é o token de concorrência.

## 6. Tests

TDD por tarefa (padrões §11.3; domain e application ≥ 80%). Unitários: `Transitions` (cada guarda), `CursorCodec`, validação do request, validação de boot. Integração (Testcontainers Postgres, `*IT`): cada handler contra SQL real (inclusive duplicata e fora de ordem), corrida `PaymentApproved` × scanner com 2 threads, `POST` concorrente com a mesma chave (um pedido só), BOLA com RLS (usuário B → 404), estorno único com aprovação ×3. Contrato: eventos produzidos validam contra `contracts/examples`. Cenário: `happy-path.sh` e F1, F3, F5, F6, F7, F8.

## 7. Docs

README do serviço (API, eventos, variáveis); `docs/demo/versioning.md` aponta para o novo arquivo de publicação do `OrderCreated`; `docs/runbook.md` só muda se um comando mudar.

## 8. Out of scope

- Timeout para pedido preso em `PENDING` (inventory nunca respondeu). Hoje o inventory sempre responde (evento ou expiração); um scanner de `PENDING` entraria se a demo mostrasse o furo.
- Reprocessar a DLQ automaticamente (manual por `scripts/dlq.sh`).
- Preço calculado no order: o preço vem do `InventoryReserved`.

## 9. Risks

- **Corrida deadline × aprovação** é a lógica mais fina do serviço. Mitigação: as duas decisões são `UPDATE` no mesmo banco, com teste de integração de duas threads.
- **Cancelamento sem `correlationId` original** (o `orders` não o guarda): os eventos do scanner nascem com um novo UUID e o trace do timeout fica solto do trace da compra. Mitigação: Decisão 5 (`OPEN`).
- **Pedido `PENDING` eterno** se a mensagem do inventory for para a DLQ (Seção 8).
- **Relay em backoff após o broker voltar** pode empurrar `PaymentRequested` além do deadline (F6). Isso é o desenho: o scanner cancela e o estorno cobre aprovação tardia.

## 10. Unresolved questions

- Mapeamento de `InventoryRejected(SECTOR_NOT_FOUND | INVALID_QUANTITY)` para o `OrderCancelled` (Decisão 4): precisa de ok do grupo, porque estender o enum é mudança de contrato (RFC 0001 §5.4).
- Guardar `correlation_id` no pedido (Decisão 5, hoje UUID novo no scanner) exigiria `V0007`.

## 11. Decisions

| # | Grade | Decision |
|---|---|---|
| 1 | `LOCKED` | Toda transição é um `UPDATE … WHERE id = ? AND status = <esperado> … RETURNING`; zero linhas → `StaleEventException`. Nada de ler-e-depois-escrever. Aplicar a saga de outro jeito invalida os testes de corrida e o argumento do ADR-0003. |
| 2 | `LOCKED` | `POST /orders` grava `orders` + outbox numa só TX no pool **app** (RLS) e responde `202` antes de qualquer efeito; `Idempotency-Key` obrigatório (UUID) e único por usuário. |
| 3 | `LOCKED` | `payment_deadline_at` usa o `now()` do banco e entra no mesmo `UPDATE` que leva a `AWAITING_PAYMENT`; a confirmação exige `payment_deadline_at >= now()`. |
| 4 | `ASSUMED` | `InventoryRejected` de qualquer `reason` cancela como `OUT_OF_STOCK` (comportamento do código antigo). Divergência com o contrato registrada em §3 e no plano. |
| 5 | `ASSUMED` | `correlationId`: os handlers propagam o do evento consumido; o deadline scanner gera um UUID novo por cancelamento (como no código antigo), sem `V0007`. Decidido em T11: o custo é só o trace do timeout solto no Jaeger; a coluna `orders.correlation_id` entra se a demo precisar. |
| 6 | `ASSUMED` | `GET /orders` pagina por cursor opaco (base64 de `createdAt|id`), `limit` padrão 20, máximo 50. |
| 7 | `ASSUMED` | Repetição de `Idempotency-Key` responde `200` com o pedido completo (padrões §4), mesmo se o primeiro `POST` ainda não terminou a saga. |
| 8 | `ASSUMED` | `ORDER_MAX_QUANTITY` ≤ 6 (o `CHECK` do banco é a última defesa) e falha no boot se maior. |
| 9 | `ASSUMED` | `OrderCreated` v2 com `channel = "API"` é o padrão; v1 só via `ORDER_CREATED_VERSION=1` (demo de versionamento). |
| 10 | `ASSUMED` | Estorno único garantido por `refund_status IS NULL` na guarda; o payment garante o resto por `refunds.payment_id UNIQUE`. |

## 12. Phasing

- **P1 (T09):** API e criação (`POST`/`GET`), BOLA, idempotência. Habilita o `OrderCreated` para o inventory.
- **P2 (T10):** handlers de reserva/pagamento/compensação simples. Fecha o `happy-path.sh`, F1, F5.
- **P3 (T11):** deadline scanner, `ReservationExpired`, estorno tardio, `PaymentRefunded`. Fecha F7 e F6.
