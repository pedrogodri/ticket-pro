# F3 Scenarios Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Os scripts `scripts/f1..f8` do checkpoint da F3 (roadmap §F3; arquitetura §11; RFC 0003 §5.4–5.8), cada um com verificação automática (exit 0 ou 1), mais `scripts/dlq.sh list|show|redrive <svc>`.

**Architecture:**
- `scripts/lib/scenario.sh` reúne o que os cenários compartilham:
  - `pass`/`fail`/`die`;
  - cadastro e login com retry respeitando `Retry-After` (o gateway limita login por IP);
  - criação e polling de pedido;
  - `sql <db> <query>` (psql no container do banco, como owner, só para verificação e preparo);
  - `publish` no SNS com os atributos `type`/`version`.
- Cada `fN-*.sh` deixa a stack como encontrou, inclusive em caso de falha (`trap`).
- Variáveis do payment que os cenários mudam (`PAYMENT_FAILURE_RATE`, `PAYMENT_LATENCY_MS`) passam a ser interpoladas no compose do payment, como já acontece com `ORDER_CREATED_VERSION`.

**Spec:** `docs/arquitetura.md` §11 (tabela F1–F8); RFC 0003 §5.4, §5.6–5.8.

## Global Constraints
- Só pelo gateway para o que o cliente faz. Banco e SNS só para verificar e para injetar mensagens.
- Os scripts não dependem de estado anterior. O F5 restaura o setor `F5` (10 unidades) antes de rodar.
- Saída `ok`/`FAIL`/`info` e linha final `fN: <n> failed`, como `happy-path.sh`.

## Cenários
| Script | Gatilho | Verificação |
|---|---|---|
| `f1-compensation.sh` | `PAYMENT_FAILURE_RATE=1` | pedido `CANCELLED(PAYMENT_FAILED)` e `available` volta ao valor anterior |
| `f2-consumer-kill.sh` | `docker kill` numa réplica do inventory com mensagens em voo | todas as reservas criadas uma vez, a outra réplica assume |
| `f3-duplicate.sh` | mesmo envelope `OrderCreated` 5× | 1 reserva e 4 logs `duplicate=true` |
| `f4-poison.sh` | `OrderCreated` inválido | vai para a DLQ do inventory depois de 3 recebimentos, e um pedido válido em seguida é processado |
| `f5-race.sh` | 100 `POST /orders` concorrentes (20 usuários × 5) no setor F5 | `available = 0`, 10 pedidos não rejeitados, 90 `CANCELLED(OUT_OF_STOCK)` |
| `f6-broker-down.sh` | `docker pause ministack` por 30 s com um pedido criado | o pedido fica `PENDING`, o outbox cresce, e depois do `unpause` ele chega a estado final |
| `f7-late-response.sh` | `PAYMENT_LATENCY_MS` > `ORDER_PAYMENT_TIMEOUT_MS` | `CANCELLED(PAYMENT_TIMEOUT)` e `refund_status = REFUNDED` (depende do estorno no order e no payment) |
| `f8-scale.sh` | `--scale payment-service=3` | 3 réplicas consumindo, 10 pedidos com estado final e nenhuma cobrança duplicada |

## Review Focus
1. **Script interrompido no meio** (Ctrl+C, falha) → a stack volta ao normal (`unpause`, envs restauradas, escala original).
2. **Rate limit do gateway** não faz o F5 nem os cadastros falharem: retry com `Retry-After`.
3. **Reexecução** de qualquer script passa de novo (sem depender de dados de execuções anteriores).
4. **F7 com o estorno ainda ausente** → falha com mensagem que diz o que falta, sem travar.
5. **`dlq.sh redrive`** devolve as mensagens à fila de origem, e `list` mostra a contagem.
