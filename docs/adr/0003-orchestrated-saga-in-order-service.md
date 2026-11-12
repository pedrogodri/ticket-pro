# ADR-0003: Saga de compra orquestrada pelo order-service

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Matheus, Pedro, Gabriel
- **Tags**: saga, consistência

## Contexto e problema

A compra envolve três serviços com bancos próprios (order, inventory, payment). Não há transação distribuída (2PC). Precisamos de consistência eventual com compensação (requisito obrigatório), timeout de pagamento e tratamento de resposta tardia (F7).

## Direcionadores

- O estado do pedido tem que ser consultável num lugar só (`GET /orders/{id}`).
- Timeouts e compensações precisam ser explícitos e fáceis de demonstrar.
- O fluxo precisa ser fácil de explicar na apresentação.

## Opções consideradas

- Orquestração no order-service (máquina de estados + eventos de comando)
- Coreografia pura (cada serviço reage a eventos dos outros)
- Motor de workflow (Temporal / Step Functions)

## Decisão

Escolhemos a **orquestração no order-service**. A máquina de estados é `PENDING → AWAITING_PAYMENT → CONFIRMED | CANCELLED(reason)`, e cada transição é um `UPDATE ... WHERE status = <esperado>` na mesma transação do outbox. O order emite os comandos `PaymentRequested` / `RefundRequested` e os fatos `OrderCreated` / `OrderConfirmed` / `OrderCancelled`. Um deadline scanner (`SKIP LOCKED`) cancela pedidos `AWAITING_PAYMENT` vencidos. A saga de **cadastro** (auth ↔ user) é curta e fica coreografada.

### Consequências positivas

- Todos os timeouts, compensações e reembolsos ficam num único lugar.
- Evento fora de ordem ou duplicado não regride o estado.

### Consequências negativas

- O order-service conhece os passos do fluxo (mais acoplamento lógico que na coreografia).
- O deadline scanner e o TTL da reserva precisam de configuração coerente (`RESERVATION_TTL_MS >= ORDER_PAYMENT_TIMEOUT_MS + margem`).

## Prós e contras

### Orquestração ✅
- ✅ Fluxo visível e fácil de testar.
- ❌ O orquestrador concentra lógica.

### Coreografia
- ✅ Baixo acoplamento.
- ❌ O fluxo fica espalhado, o timeout de pagamento fica sem dono claro e a demonstração é difícil.

### Temporal/Step Functions
- ✅ Retries e timers prontos.
- ❌ Mais infraestrutura, curva de aprendizado alta para o prazo e o Step Functions não é essencial ao enunciado.

## Links

- [docs/arquitetura.md §6](../arquitetura.md), RFC-003
