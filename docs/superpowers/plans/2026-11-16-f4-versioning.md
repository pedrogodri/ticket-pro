# F4 Versionamento Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Bônus de versionamento (RFC 0001 §5.5): o inventory aceita `OrderCreated` v1 e v2 por um upcaster, o order publica a versão escolhida por `ORDER_CREATED_VERSION` e `version: 3` vai para a DLQ.

**Architecture:** No inventory, `upcastOrderCreated` converte v1 → v2 (`channel = "API"`) antes do único caminho de processamento. O handler declara `Versions() = [1, 2]`, e o consumidor da plataforma já trata versão não suportada como venenosa (DLQ). O canal fica gravado na reserva (migration V0007) para métricas. No order, `createOrder` publica v1 ou v2 conforme a config.

**Tech Stack:** Go (inventory), TypeScript (order), contratos em `contracts/events/OrderCreated/{v1,v2}`.

**Spec:** `docs/rfc/0001-message-contract-and-event-versioning.md` §5.4–5.5.

## Global Constraints
- `ORDER_CREATED_VERSION` ∈ {1, 2}, padrão 2. v2 exige `channel` ∈ {API, WEB, MOBILE}.
- O order publica `channel = "API"`, porque todo pedido entra pela API HTTP.
- Migration nova `V0007`; nunca editar as aplicadas.

## Review Focus
1. **v1 e v2 na mesma fila** → as duas versões reservam pelo mesmo caminho; v1 grava `channel = API`. Teste na Task 1.
2. **`version: 3`** → venenosa e vai para a DLQ sem efeito. Demo na Task 3, contra o consumidor real.
3. **v2 com `channel` fora do enum** → rejeitada pelo schema (venenosa). Teste na Task 1.
4. **`ORDER_CREATED_VERSION` inválido** → o order não sobe. Teste na Task 2.
5. **Reentrega de v1 depois de v2 para o mesmo pedido** → uma reserva só (`order_id UNIQUE` + idempotência). Já coberto pelo R1 da plataforma.

### Task 1: Upcaster no inventory
- [ ] Testes: v1 → `channel API`; v2 `WEB` grava `WEB`; `Versions()` = [1, 2]; contrato rejeita v2 sem `channel`. Migration `V0007__add_reservation_channel.sql`.
- [ ] Commit `feat(inventory-service): accept OrderCreated v1 and v2 through an upcaster`.

### Task 2: `ORDER_CREATED_VERSION` no order
- [ ] Testes: config padrão 2, valor 3 → erro; outbox com v1 sem `channel` e v2 com `channel: "API"`, ambos válidos no contrato.
- [ ] Atualizar `contracts/asyncapi.yaml` (o order publica v2; o inventory recebe v1 e v2) e rodar `make -C contracts check`.
- [ ] Commit `feat(order-service): publish OrderCreated v1 or v2 by ORDER_CREATED_VERSION`.

### Task 3: Demo
- [ ] `scripts/demo-versioning.sh`: publica v1, v2 (`WEB`) e v3 no tópico `ticketpro-orders`. Confere v1 → reserva `API`, v2 → reserva `WEB` (log `reservation_created` com channel), v3 → DLQ do inventory.
- [ ] Atualizar o README do inventory e `docs/demo/versioning.md`.
