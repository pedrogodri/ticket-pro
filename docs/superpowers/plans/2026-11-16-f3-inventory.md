# F3 Inventory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Cumprir a parte do inventory na F3 (roadmap §F3): liberação idempotente, job de expiração de reservas e validação TTL × deadline no boot.

**Architecture:** Um `Expirer` em `internal/modules/reservations` roda em loop com o pool worker. A cada tick, numa transação, ele trava as reservas `PENDING` vencidas (`FOR UPDATE SKIP LOCKED`, seguro em N réplicas). Para cada uma: marca `RELEASED`, devolve o estoque e grava `InventoryReservationExpired` no outbox. O config recusa subir se `RESERVATION_TTL_MS < ORDER_PAYMENT_TIMEOUT_MS + RESERVATION_SAFETY_MARGIN_MS`.

**Tech Stack:** Go 1.25, pgx, shared/go (outbox, envelope), Testcontainers.

**Spec:** `docs/arquitetura.md` §6 passos 7 e 9; `docs/rfc/0003-failure-handling-strategy.md` §5.1 e §5.7 (decisão 4, `LOCKED`).

## Global Constraints

- Envs: `RESERVATION_TTL_MS` 60000, `ORDER_PAYMENT_TIMEOUT_MS` 15000, `RESERVATION_SAFETY_MARGIN_MS` 30000, `RESERVATION_EXPIRY_SCAN_INTERVAL_MS` 1000, `RESERVATION_EXPIRY_BATCH_SIZE` 50.
- O evento segue `contracts/events/InventoryReservationExpired/v1` (`orderId`, `reservationId`, `quantity`).
- Liberar e devolver o estoque acontecem na mesma transação que o outbox. Nunca editar migration aplicada.

## Review Focus

1. **Duas réplicas varrendo ao mesmo tempo** → cada reserva liberada uma vez, estoque devolvido uma vez. Teste na Task 2.
2. **`OrderCancelled` depois da expiração** (ou expiração depois do cancelamento) → o estoque volta só uma vez. Testes nas Tasks 1 e 2.
3. **Reserva `CONFIRMED` vencida** → nunca liberada. Teste na Task 2.
4. **TTL abaixo de deadline + margem** → o serviço não sobe e o erro nomeia as variáveis. Teste na Task 3.
5. **Banco fora durante o tick** → o loop loga e continua, sem derrubar o processo. Teste na Task 2 (erro em `RunOnce` não encerra `Run`).

### Task 1: Liberação idempotente
- [ ] Testes: `TestOrderCancelled_AfterConfirmationIsStale` (estoque não volta) e `TestOrderCancelled_AfterExpiryIsStale`.

### Task 2: Job de expiração
- **Produces:** `reservations.Expirer{Pool *pgxpool.Pool; TopicARN string; Interval time.Duration; BatchSize int; Logger *slog.Logger}`, com `RunOnce(ctx) (int, error)` e `Run(ctx) error`.
- [ ] Testes de integração:
  - vencida → `RELEASED`, estoque de volta e outbox válido;
  - não vencida e `CONFIRMED` → intactas;
  - duas chamadas concorrentes de `RunOnce` → total liberado = 1;
  - respeita `BatchSize`.
- [ ] Wiring no `main.go` e envs. Commit `feat(inventory-service): expire pending reservations and publish InventoryReservationExpired`.

### Task 3: Validação no boot
- [ ] Teste de config: TTL 40000 com timeout 15000 e margem 30000 → erro citando `RESERVATION_TTL_MS`. Padrões válidos passam.
- [ ] Commit `feat(inventory-service): refuse to start when reservation TTL is shorter than the payment deadline plus margin`.

### Task 4: Verificação
- [ ] `make lint typecheck test test-integration` no inventory, `check-autonomy`, `happy-path.sh` na stack limpa e checagem ao vivo da expiração (TTL curto via override) com o `available` voltando. README do inventory atualizado.
