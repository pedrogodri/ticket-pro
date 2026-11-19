# RFC 0011 — Design do notification-service em Go

- **Status:** 📝 Draft (serviço completo; este RFC documenta o estado e o que fica de fora — [plano](../superpowers/plans/2026-11-19-notification-service.md))
- **Scope:** O `notification-service` (Go): consumo de `TicketsIssued` e `OrderCancelled`, comprovante no S3 e registro de notificações. É o serviço que prova "evento com 2+ consumidores" e a categoria de nuvem S3. Documenta contrato de comportamento, falhas e lacunas de verificação. **Sem mudança de contrato e sem migration.** Não cobre o envio real de e-mail/SMS (fora do escopo).
- **Related:** [ADR-0006](../adr/0006-idempotent-consumers-processed-messages.md), [ADR-0010](../adr/0010-cloud-services-selection.md), [RFC 0003 §5.4](0003-failure-handling-strategy.md), [arquitetura §6 passo 6](../arquitetura.md), [`services/notification-service/`](../../services/notification-service/README.md), [`contracts/events/TicketsIssued`](../../contracts/events/TicketsIssued/v1.schema.json), [`scripts/happy-path.sh`](../../scripts/happy-path.sh)

---

## 1. Summary

Ao consumir `TicketsIssued`, grava `receipts/{orderId}.json` no bucket `ticketpro-receipts` e registra `notifications(order_id, kind)`. Ao consumir `OrderCancelled`, registra a notificação. A chave do objeto é determinística e o registro tem `UNIQUE (order_id, kind)`, então reentrega é inócua.

## 2. Motivation

Atende dois itens da avaliação: evento com dois ou mais consumidores (`TicketsIssued` vai ao user e ao notification) e uso de nuvem além da mensageria (S3). Como consome o fim da saga, só fecha o `happy-path.sh` quando inventory e order Java emitirem os eventos.

## 3. Current state

Verificado em 19/11 em `services/notification-service`:

| Item | Estado | Evidência |
|---|---|---|
| `TicketsIssued` → `PutObject receipts/{orderId}.json` (JSON com pedido, usuário, evento, setor, ingressos, `issuedAt`) e `INSERT … ON CONFLICT (order_id, kind) DO NOTHING` | ✅ | `internal/modules/receipts/receipts.go` |
| `OrderCancelled` → registro `ORDER_CANCELLED` | ✅ | idem |
| Escrita no S3 **antes** do commit; falha do S3 é transitória (nada registrado, retry do SQS) | ✅ | comentário e código do handler |
| Migrations V0001–V0003 (`processed_messages`, `notifications` com `kind CHECK`) e sem outbox | ✅ | `migrations/` |
| Readiness com `db` e `broker`; tracing HTTP, pgx e mensagens | ✅ | README, `traced_test.go` |
| Teste de integração do módulo e teste de contrato | ✅ | `receipts_integration_test.go`, `contract_test.go` |
| Verificação ponta a ponta do objeto no S3 | parcial | `happy-path.sh` confere o objeto, mas depende do order/inventory Java |
| README (fase F1/F2 como pendentes) | ⚠️ desatualizado | estado diz "completo" e "Por onde começar" ainda lista F1/F2 |

## 4. Goals / Non-goals

**Goals**

- Um comprovante por pedido confirmado, um registro por (pedido, tipo), sob qualquer duplicidade.
- Objeto legível a partir do script de demonstração.

**Non-goals**

- Entrega real (e-mail, push), templates, preferências do usuário: o "envio" é o registro em `notifications` e o objeto no S3, declarado assim na apresentação.
- API HTTP, outbox, eventos publicados.

## 5. Design

### 5.1 Fluxo

```
TicketsIssued → PutObject(receipts/{orderId}.json) → TX: processed_messages + notifications(TICKETS_ISSUED) → commit → ack
OrderCancelled →                                     TX: processed_messages + notifications(ORDER_CANCELLED) → commit → ack
```

Reescrever o mesmo objeto é idempotente porque a chave é determinística e o conteúdo é função do evento (`issuedAt` vem do `occurredAt` do envelope, não do relógio local).

### 5.2 Eventos e dados

| Consome (`ticketpro-notification-service`) | Publica |
|---|---|
| `TicketsIssued` v1, `OrderCancelled` v1 | — |

`notifications(id, order_id, kind ∈ TICKETS_ISSUED, ORDER_CANCELLED, payload jsonb, created_at, UNIQUE(order_id, kind))` e `processed_messages`.

### 5.3 Invariantes

1. No máximo um comprovante e uma notificação por (pedido, tipo).
2. Objeto no S3 só com `TicketsIssued` válido; a notificação só depois do objeto.
3. Ack só depois do commit.

### 5.4 Falhas

| Falha | Comportamento |
|---|---|
| S3 fora | handler falha, mensagem volta; 3 recebimentos → DLQ; `/health/ready` não reflete S3 (ver Riscos) |
| Banco fora | `/health/ready` 503 |
| Duplicata ×5 | 1 objeto (mesma chave), 1 linha |
| Payload inválido | venenosa → DLQ (`ErrInvalidMessage`) |
| `OrderCancelled` e `TicketsIssued` do mesmo pedido | tipos distintos, sem conflito; não deveria ocorrer para o mesmo pedido |

### 5.5 Observabilidade e configuração

Env: `QUEUE_URL`, `CONTRACTS_DIR`, `RECEIPTS_BUCKET`, `DATABASE_URL`, `DATABASE_WORKER_URL`, `CONSUMER_CONCURRENCY`, `SQS_WAIT_TIME_S`, `HTTP_CLIENT_TIMEOUT_MS`. O comprovante não contém e-mail nem dados sensíveis, só `userId`.

## 6. Tests

Existentes: integração do módulo (Postgres) e contrato. A acrescentar: teste de integração contra o MiniStack que confira o objeto lido do S3 e a reentrega ×5; verificação em `happy-path.sh` de que o objeto existe **e** de que `OrderCancelled` gera a notificação (hoje só o objeto é conferido).

## 7. Docs

Atualizar o README (estado e "Por onde começar") e declarar na apresentação o que "notificar" significa.

## 8. Out of scope

Entrega real, retenção do bucket, política de ciclo de vida do S3.

## 9. Risks

- **S3 fora não aparece no readiness:** a falha só se revela pelo retry/DLQ. Aceitável para a demo; documentar.
- **Dependência do inventory Java:** sem `TicketsIssued` o serviço fica ocioso; não é defeito dele.
- **Comprovante sem usuário legível:** só `userId`; adequado, mas lembrar na apresentação.

## 10. Unresolved questions

Nenhuma bloqueante.

## 11. Decisions

| # | Grade | Decision |
|---|---|---|
| 1 | `LOCKED` | Chave do objeto `receipts/{orderId}.json` determinística; reentrega reescreve o mesmo objeto (ADR-0006). |
| 2 | `ASSUMED` | "Enviar" a notificação = registrar em `notifications` e gravar o objeto; sem canal externo. |
| 3 | `ASSUMED` | S3 fora não derruba o readiness (broker fora não derruba, RFC 0003 Decisão 8). |

## 12. Phasing

Único passo: README e verificações ponta a ponta quando order e inventory Java estiverem verdes.
