# RFC 0006 — Design do inventory-service em Java

- **Status:** 📝 Draft (design fechado — implementação nas tarefas T05–T08 e T13 do [plano](../superpowers/plans/2026-11-19-java-order-inventory.md))
- **Scope:** O `inventory-service` (Java 21, Spring Boot): catálogo (`GET /events`, `GET /events/{id}`) sobre o seed já migrado, reserva atômica anti-overselling (R2), upcaster `OrderCreated` v1→v2, confirmação de reserva com emissão de ingressos (`TicketsIssued`), devolução de estoque em `OrderCancelled`, job de expiração de reservas e validação TTL × deadline no boot. Usa a camada [RFC 0004](0004-camada-platform-em-java-para-order-e-inventory.md). **Sem mudança de contrato e sem migration nova.**
- **Related:** [ADR-0006](../adr/0006-idempotent-consumers-processed-messages.md), [ADR-0007](../adr/0007-conditional-update-prevents-overselling.md), [ADR-0021](../adr/0021-order-and-inventory-in-java.md), [RFC 0001 §5.4–5.5](0001-message-contract-and-event-versioning.md), [RFC 0003 §5.1, §5.7, §5.8](0003-failure-handling-strategy.md), [arquitetura §6](../arquitetura.md), [`docs/demo/versioning.md`](../demo/versioning.md), [`services/inventory-service/migrations/`](../../services/inventory-service/migrations/V0006__create_reservations_tickets.sql), [`contracts/events/`](../../contracts/asyncapi.yaml). Comportamento de referência (Go removido): `git show 65d538c:services/inventory-service/internal/modules/...`.

---

## 1. Summary

O inventory é dono de eventos, setores, estoque, reservas e ingressos. Ao consumir `OrderCreated` (v1 ou v2, via upcaster) ele faz, numa única transação, `UPDATE sectors SET available = available - q WHERE … AND available >= q`, cria `reservations(PENDING, expires_at)`, registra `processed_messages` e grava `InventoryReserved` (ou `InventoryRejected`) no outbox. `OrderConfirmed` confirma a reserva e emite um ingresso por unidade (`TicketsIssued`); `OrderCancelled` e o job de expiração devolvem o estoque **uma única vez**, porque a liberação é um `UPDATE reservations … WHERE status = 'PENDING'`.

## 2. Motivation

R2 (zero venda dupla) e F5 (100 pedidos × 10 unidades) vivem aqui; `GET /events` é o primeiro passo do `happy-path.sh`; F1, F2, F3, F4 e o bônus de versionamento tocam este serviço. Hoje ele só responde `/health/*`. O Go removido (ADR-0021) tinha as regras certas, e o plano é portá-las com a mesma semântica, mais um cuidado novo: em Java/JDBC a TX do consumidor é explícita, e o tratamento de erro no meio da reserva precisa ser pensado (5.3).

## 3. Current state

Verificado em 19/11:

- Código: `InventoryApplication`, `HealthController`, `application.yml`. Migrations V0001–V0007 prontas: `events`, `sectors` (`CHECK (available >= 0 AND available <= capacity)`), `reservations` (`order_id UNIQUE`, índice parcial `PENDING` por `expires_at`, `channel` ∈ {API, WEB, MOBILE} com padrão `API`), `tickets` (`code UNIQUE`), `outbox`, `processed_messages`. Nenhuma tabela do inventory tem RLS (o catálogo é público para usuário autenticado).
- Seed (V0005): *Festival de Verão 2026* (`…3333-…0003`) com Pista (500), VIP (100) e o setor **`F5` com 10 unidades**; *Show Acústico* com Plateia (200). Preços em centavos, `BRL`.
- `.env.example`: `INVENTORY_TOPIC_ARN`, `QUEUE_URL`, `RESERVATION_TTL_MS=60000`, `ORDER_PAYMENT_TIMEOUT_MS=15000`, `RESERVATION_SAFETY_MARGIN_MS=30000`, `RESERVATION_EXPIRY_SCAN_INTERVAL_MS=1000`, `RESERVATION_EXPIRY_BATCH_SIZE=50`, `RESERVATION_EXPIRY_BATCH_PAUSE_MS=50`. O `compose.yaml` sobe 2 réplicas e já usa `wget` no healthcheck (o README ainda cita o binário Go).
- Contratos: consome `OrderCreated` v1/v2, `OrderConfirmed`, `OrderCancelled`; publica `InventoryReserved`, `InventoryRejected` (`OUT_OF_STOCK | SECTOR_NOT_FOUND | INVALID_QUANTITY`), `InventoryReservationExpired`, `TicketsIssued` (`tickets[]` de 1 a 6 itens `{ticketId, code}`; `code` casa `^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$`).
- Os scripts dependem de: `GET /events` → `.items[].id`; `GET /events/{id}` → `.sectors[] {id, available}`; `reservations.status = 'RELEASED'` (F1); `processed_messages`/`reservations` por `order_id` (F2, F3, F4); log `"duplicate":true` e `"message_rejected"` (F3, F4).
- **Divergências registradas (não decididas aqui):** (a) `OrderCancelled` não tem motivo para `SECTOR_NOT_FOUND`/`INVALID_QUANTITY` (ver RFC 0005 §3); (b) `CONTRACTS_DIR` do `.env.example` (`/contracts`) difere do `COPY contracts /app/contracts` do `Dockerfile` (ver RFC 0004 §3).

## 4. Goals / Non-goals

**Goals**

- R2: 100 reservas concorrentes num setor de 10 resultam em **exatamente 10** reservas e `available = 0`, mesmo com 2 réplicas.
- Liberação idempotente: estoque devolvido uma vez, mesmo com `OrderCancelled` ×N, expiração e cancelamento concorrentes.
- Um único caminho de processamento para `OrderCreated` v1 e v2.
- Falha segura: erro no meio da reserva nunca deixa estoque decrementado sem reserva.

**Non-goals**

- Assento numerado (arquitetura §12) e sharding do contador (ADR-0007, caminho de evolução).
- API administrativa de catálogo; o catálogo vem de seed.
- Cobrança e pedido (outros serviços).

## 5. Design

### 5.1 Estrutura (padrões §2.2)

```
br.furb.ticketpro.inventory
├── InventoryApplication
├── platform/                       # RFC 0004
└── modules/
    ├── catalog/       web/{EventController,CursorCodec}, application/CatalogQueries, infrastructure/CatalogRepository
    ├── reservations/  application/{ReserveStock (OrderCreatedHandler), ReleaseStock (OrderCancelledHandler), ReservationExpirer (PeriodicJob)},
    │                  domain/{OrderCreatedV2, Upcaster}, infrastructure/ReservationRepository
    └── tickets/       application/IssueTickets (OrderConfirmedHandler), domain/TicketCode
```

### 5.2 Catálogo (pool app)

`GET /events?limit=&cursor=` → `{items:[{id,name,startsAt,venue}], nextCursor}` (cursor keyset `(starts_at, id)`, `limit` ≤ 50, padrão 20, ordem `starts_at, id`). `GET /events/{id}` → evento + `sectors[{id,name,capacity,available,unitPriceCents,currency}]` ordenados por preço desc, nome. `id` inválido ou inexistente → `404`. Exige `X-User-Id` (o gateway valida o JWT; sem o header, `401`). Mesmo formato do serviço antigo, que é o que os scripts leem.

### 5.3 Reserva atômica (R2, ADR-0007)

`OrderCreatedHandler` (`versions() = {1, 2}`), pool worker, dentro da TX do consumidor:

```sql
-- 0) idempotência de negócio, antes de qualquer efeito
SELECT 1 FROM reservations WHERE order_id = ?          -- existe → StaleEventException
-- 1) R2
UPDATE sectors SET available = available - ?
 WHERE id = ? AND event_id = ? AND available >= ?
RETURNING unit_price_cents, currency
-- 2) sem linha: SELECT EXISTS (setor com esse event_id?) → InventoryRejected(SECTOR_NOT_FOUND | OUT_OF_STOCK)
-- 3) reserva
INSERT INTO reservations (id, order_id, sector_id, quantity, unit_price_cents, total_cents,
                          status, expires_at, channel)
VALUES (?, ?, ?, ?, ?, ?, 'PENDING', now() + (? * interval '1 millisecond'), ?)
```

Depois, `InventoryReserved {orderId, reservationId, eventId, sectorId, quantity, unitPriceCents, totalCents, currency, expiresAt}` no outbox. `quantity < 1` → `InventoryRejected(INVALID_QUANTITY)` sem tocar o estoque. `expires_at` usa o `now()` do banco (e o valor de `expiresAt` do evento é lido do `RETURNING expires_at`, não calculado na JVM).

**Cuidado de TX:** o passo 1 já decrementou. Se o passo 3 falhar (inclusive `23505` em `order_id`, que só ocorre se duas réplicas processarem o mesmo pedido com `messageId` diferentes), a exceção **propaga**: o rollback desfaz o decremento, a mensagem volta (transitória) e o reprocessamento cai no `SELECT` do passo 0 → `STALE`. O handler nunca captura essa exceção para commitar. É a diferença para o código Go (uma TX por `pgx.Tx` com a mesma regra), e tem teste próprio (§6).

Rejeitar **é um sucesso** do ponto de vista do consumidor: `InventoryRejected` entra no outbox e a TX comita (pedido sem estoque não é erro).

### 5.4 Upcaster `OrderCreated` v1 → v2 (RFC 0001 §5.5)

Modelo interno = v2 (`orderId, userId, eventId, sectorId, quantity, channel`). `version == 1` → `channel = "API"`; `version == 2` → usa o `channel` do payload; outra versão nunca chega ao handler (o `SqsConsumer` rejeita por `versions()`/`ContractValidator`: `OrderCreated` v3 vai à DLQ sem efeito, que é o roteiro de `scripts/demo-versioning.sh`). Um caminho de processamento só.

### 5.5 Confirmação e ingressos (F-happy)

`OrderConfirmedHandler` (`versions() = {1}`):

```sql
UPDATE reservations r SET status = 'CONFIRMED'
  FROM sectors s JOIN events e ON e.id = s.event_id
 WHERE r.order_id = ? AND r.status = 'PENDING' AND s.id = r.sector_id
RETURNING r.id, r.quantity, s.id, s.name, e.id, e.name, e.starts_at
```

Zero linhas → `StaleEventException`; se a reserva existe e está `RELEASED`, loga **`order_confirmed_after_release` em ERROR** (o cliente pagou e não recebeu: a invariante de TTL deveria impedir). Com linha: insere `quantity` ingressos (`code` aleatório XXXX-XXXX-XXXX com `SecureRandom`, alfabeto sem `O/0/I/1`) e enfileira `TicketsIssued {orderId, userId, eventId, eventName, eventStartsAt, sectorId, sectorName, tickets[{ticketId, code}]}`. `userId` vem do `OrderConfirmed`. Colisão de `tickets.code` aborta a TX e a mensagem é reprocessada (transitória), nunca descartada.

### 5.6 Devolução de estoque (F1)

`OrderCancelledHandler` (`versions() = {1}`):

```sql
WITH released AS (UPDATE reservations SET status = 'RELEASED'
                   WHERE order_id = ? AND status = 'PENDING' RETURNING sector_id, quantity)
UPDATE sectors s SET available = s.available + r.quantity FROM released r WHERE s.id = r.sector_id
```

Zero linhas → `StaleEventException` (nada reservado, já liberado, já confirmado: no-op). É o que torna a liberação idempotente e segura contra expiração concorrente: só quem muda `PENDING → RELEASED` devolve.

### 5.7 Job de expiração (rede de segurança)

`ReservationExpirer implements PeriodicJob` (RFC 0004 §5.7), a cada `RESERVATION_EXPIRY_SCAN_INTERVAL_MS`, lote `RESERVATION_EXPIRY_BATCH_SIZE`, pausa `RESERVATION_EXPIRY_BATCH_PAUSE_MS` entre lotes cheios. Numa TX do pool worker:

1. `WITH expired AS (SELECT id FROM reservations WHERE status='PENDING' AND expires_at < now() ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED) UPDATE reservations r SET status='RELEASED' FROM expired x WHERE r.id = x.id RETURNING r.id, r.order_id, r.sector_id, r.quantity`;
2. soma a quantidade **por setor** e faz **um `UPDATE sectors` por setor, em ordem de `sector_id`** (réplicas com lotes que dividem setores pegam os locks na mesma ordem e não geram deadlock `40P01`);
3. um `InventoryReservationExpired {orderId, reservationId, quantity}` por reserva no outbox. Reserva `CONFIRMED` nunca é tocada (a guarda é `status = 'PENDING'`).

O `correlationId` desses eventos é um UUID novo (a reserva não o guarda; o order correlaciona por `orderId`).

### 5.8 Validação de TTL no boot (RFC 0003 decisão 4)

`AppConfig.from` recusa subir se `RESERVATION_TTL_MS < ORDER_PAYMENT_TIMEOUT_MS + RESERVATION_SAFETY_MARGIN_MS` e nomeia as três variáveis na mensagem. Mesma regra no order. Com os padrões (60000 ≥ 15000 + 30000) sobe.

### 5.9 R2 sob concorrência

O gargalo é a linha do setor: o Postgres serializa o `UPDATE`, então as duas réplicas (`CONSUMER_CONCURRENCY=4` cada, virtual threads) nunca enxergam o mesmo `available`. A TX é curta e sem rede. O `CHECK (available >= 0)` é a última defesa; no F5 o contador atinge 0 e os 90 restantes recebem `InventoryRejected(OUT_OF_STOCK)`.

### Alternatives considered

- **Passo 0 (SELECT) ausente, confiando só no `UNIQUE`.** Funciona, mas toda duplicata com `messageId` novo viraria transitória e consumiria um retry (e um decremento desfeito). O `SELECT` barato evita o caso comum.
- **`INSERT … ON CONFLICT DO NOTHING` na reserva antes do decremento.** Exigiria o preço antes do `UPDATE`; inverte a ordem de R2 e cria reservas sem estoque.
- **Uma linha por unidade + `SKIP LOCKED`.** Descartado no ADR-0007 (volume e N locks por pedido).

## 6. Tests

TDD por tarefa; domain e application ≥ 80%. **Integração (Testcontainers)**: **R2** com 100 threads × setor de 10 → exatamente 10 reservas e `available = 0`; mesma mensagem ×5 → 1 reserva e 1 `processed_messages`; duas reservas do mesmo pedido com `messageId` diferentes → 1 reserva e estoque correto (cobre o cuidado de TX do §5.3); `OrderCancelled` ×N, depois da expiração e depois da confirmação → estoque devolvido ≤ 1 vez; `RunOnce` concorrente do expirer → cada reserva liberada uma vez e sem `40P01`; reserva `CONFIRMED` vencida nunca liberada; `OrderConfirmed` com reserva `RELEASED` → ERROR `order_confirmed_after_release`. **Unitário**: upcaster, `TicketCode` (formato do contrato), `CursorCodec`, validação de boot (TTL 40000 com timeout 15000 e margem 30000 → erro citando `RESERVATION_TTL_MS`). **Contrato**: toda mensagem produzida valida contra `contracts/examples`; `examples/invalid` viram poison. **Cenário**: `happy-path.sh` (catálogo e ingressos), F1, F2, F3, F4, F5, F8 e `demo-versioning.sh`.

## 7. Docs

README do serviço (corrigir o healthcheck citado e a tabela de fases); `docs/demo/versioning.md` troca o ponteiro do upcaster para o arquivo Java; `docs/arquitetura.md` não muda.

## 8. Out of scope

- Reservar mais de um setor por pedido (premissa: um setor por pedido, arquitetura §1).
- Expirar reservas por demanda (um timer por reserva): o job periódico basta.
- Estorno de ingressos já emitidos (um `OrderCancelled` depois de `CONFIRMED` não existe na saga).

## 9. Risks

- **Hotspot da linha do setor** sob carga muito alta (ADR-0007): aceitável na demo (centenas de requisições).
- **Reentrega concorrente** quando o visibility timeout vence no meio de uma reserva: a TX de uma das réplicas falha no `UNIQUE` e desfaz (5.3); a idempotência cobre o resto.
- **Deadlock entre expirer e handlers** que tocam o mesmo setor: handlers tocam um setor por TX; o expirer ordena por `sector_id`. Teste de `RunOnce` concorrente.
- **`TicketsIssued` perdido** se o relay falhar: o outbox reentrega; o user e o notification são idempotentes por `messageId`.

## 10. Unresolved questions

- Mapear `SECTOR_NOT_FOUND`/`INVALID_QUANTITY` no cancelamento do pedido: depende do grupo (RFC 0005 §10).
- `SQS_VISIBILITY_TIMEOUT_S` vs p99 de uma reserva sob F5: medir na primeira execução (RFC 0003 decisão 9).

## 11. Decisions

| # | Grade | Decision |
|---|---|---|
| 1 | `LOCKED` | A reserva é o `UPDATE sectors SET available = available - ? WHERE … AND available >= ?` (ADR-0007), na mesma TX da reserva, de `processed_messages` e do outbox. Trocar o mecanismo invalida R2 e o teste de 100 threads. |
| 2 | `LOCKED` | A liberação (`OrderCancelled` e expiração) só devolve estoque quando **ela** muda `reservations.status` de `PENDING` para `RELEASED`. |
| 3 | `LOCKED` | Erro depois de um efeito parcial (ex.: `23505` após o decremento) propaga e faz rollback; nunca se captura para commitar. |
| 4 | `ASSUMED` | O handler verifica `reservations.order_id` antes de decrementar (passo 0) e trata a existência como `STALE`. |
| 5 | `ASSUMED` | Modelo interno do `OrderCreated` é v2; v1 vira `channel = "API"`. Outras versões são venenosas (DLQ), como no código antigo. |
| 6 | `ASSUMED` | `expires_at` e a validade da reserva usam o `now()` do banco; `InventoryReserved.expiresAt` é o valor retornado pelo `INSERT`. |
| 7 | `ASSUMED` | O expirer ordena os `UPDATE sectors` por `sector_id` para evitar deadlock entre réplicas. |
| 8 | `ASSUMED` | Código do ingresso: 12 caracteres de alfabeto sem ambiguidade (`ABCDEFGHJKLMNPQRSTUVWXYZ23456789`), `SecureRandom`, formato `XXXX-XXXX-XXXX`. Colisão = reprocessamento. |
| 9 | `ASSUMED` | `GET /events` e `GET /events/{id}` usam o formato do serviço antigo (cursor keyset, `items`/`nextCursor`, `sectors[]`), porque os scripts o leem. |
| 10 | `OPEN` | Mapeamento de `InventoryRejected(SECTOR_NOT_FOUND | INVALID_QUANTITY)` no order (RFC 0005 Decisão 4): resolve o grupo, não o implementador. |

## 12. Phasing

- **P1 (T05):** catálogo (`GET /events`, `/events/{id}`): destrava o primeiro passo do `happy-path.sh`.
- **P2 (T06):** reserva + upcaster + R2 (teste de 100 threads), F3/F4/F2.
- **P3 (T07):** ingressos e devolução de estoque (F1, fim do `happy-path.sh`).
- **P4 (T08):** expirer (rede de segurança; F5/F7 sem vazamento). A validação de TTL × deadline entra antes, no `AppConfig` de T01, nos dois serviços.
- **P5 (T13):** `demo-versioning.sh` e README.
