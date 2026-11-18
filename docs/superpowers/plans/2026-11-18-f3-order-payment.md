# F3 Order + Payment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fechar as compensações da F3 que faltam (RFC 0003 §5.7), para os cenários F6 e F7 passarem:
- **order:**
  - `InventoryReservationExpired` → `CANCELLED(RESERVATION_EXPIRED)`;
  - deadline scanner → `CANCELLED(PAYMENT_TIMEOUT)`;
  - `PaymentApproved` tardio → `RefundRequested`;
  - `PaymentRefunded` → `refund_status = REFUNDED`;
  - validação TTL × deadline no boot.
- **payment:** `RefundRequested` → `refunds` + `PaymentRefunded`.

**Spec:** `docs/arquitetura.md` §6 passos 7–9; RFC 0003 §5.6–5.7 (decisão 4, `LOCKED`); contratos `RefundRequested`, `PaymentRefunded`, `InventoryReservationExpired`.

## Global Constraints
- Toda transição é `UPDATE … WHERE status = <esperado>`; sem linha, o evento é stale.
- Os eventos vão para o outbox na mesma transação. Nada de editar migrations aplicadas: `V0005` nos dois serviços.
- Envs:
  - order: `ORDER_DEADLINE_SCAN_INTERVAL_MS` 1000 e `ORDER_DEADLINE_SCAN_BATCH_SIZE` 50;
  - order e inventory: `RESERVATION_TTL_MS` 60000 e `RESERVATION_SAFETY_MARGIN_MS` 30000, injetados pelo compose a partir do `.env` da raiz.

## Review Focus
1. **Scanner e `PaymentApproved` disputando o mesmo pedido** → um só vence. Se o scanner cancela primeiro, a aprovação vira estorno.
2. **`PaymentApproved` tardio repetido** → um único `RefundRequested` (o `refund_status` guarda).
3. **`InventoryReservationExpired` para pedido já `CONFIRMED`** → stale, sem cancelar.
4. **`RefundRequested` repetido** → um estorno (`refunds.payment_id UNIQUE`).
5. **Scanner em 2 réplicas** → cada pedido cancelado uma vez (`SKIP LOCKED`).

### Task 1: order
- [ ] Migration `V0005__add_refund_status.sql`: `refund_status` ∈ {REQUESTED, REFUNDED}, nulo por padrão.
- [ ] Testes de integração, um por item do Review Focus do order, mais o cancelamento tardio dentro do `PaymentApproved` (`AWAITING_PAYMENT` vencido → `CANCELLED(PAYMENT_TIMEOUT)` + `OrderCancelled` + `RefundRequested` na mesma TX).
- [ ] `DeadlineScanner` com `runOnce`, wiring no `main` e config com validação no boot.

### Task 2: payment
- [ ] Migration `V0005__create_refunds.sql`: `refunds(id, payment_id UNIQUE, amount_cents, currency, created_at)`.
- [ ] Teste de integração: `RefundRequested` grava o estorno e publica `PaymentRefunded`; o repetido é stale.

### Task 3: verificação
- [ ] Suítes dos dois serviços, contratos, `scripts/f1..f8` a partir da stack limpa, `happy-path.sh`.
