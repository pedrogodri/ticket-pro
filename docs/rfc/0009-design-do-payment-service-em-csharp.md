# RFC 0009 — Design do payment-service em C#

- **Status:** 🚧 In progress (24/11/2026: P01, P02, P05 e P06 do [plano](../superpowers/plans/2026-11-19-payment-service.md) entregues no PR #22, mergeado; P03 (lease explícito no `f7-late-response.sh`) no PR #23. Verificado ao vivo por `scripts/payment-check.sh`, e F1 e F7 entre order e payment passaram com o `InventoryReserved` publicado à mão. Falta P04: `f1`, `f7` e `f8` com o inventory de verdade)
- **Scope:** O `payment-service` (C#, .NET 10): consumidores de `PaymentRequested` e `RefundRequested`, worker com lease, provedor simulado instável (R5), estorno e a configuração `PAYMENT_*`. Documenta o que existe, a semântica de falha e **quatro lacunas pequenas** (correlationId do worker, validação cruzada de lease × timeout, README e o env do F7). **Sem mudança de contrato; sem migration obrigatória.** Não cobre o order (RFC 0005).
- **Related:** [RFC 0003 §5.1, §5.4, §5.6, §5.7](0003-failure-handling-strategy.md), [ADR-0005](../adr/0005-transactional-outbox.md), [ADR-0006](../adr/0006-idempotent-consumers-processed-messages.md), [arquitetura §6](../arquitetura.md), [`services/payment-service/`](../../services/payment-service/README.md), [`contracts/events/PaymentApproved`](../../contracts/events/PaymentApproved/v1.schema.json), [`contracts/events/RefundRequested`](../../contracts/events/RefundRequested/v1.schema.json), [`scripts/f1-compensation.sh`](../../scripts/f1-compensation.sh), [`scripts/f7-late-response.sh`](../../scripts/f7-late-response.sh)

---

## 1. Summary

O payment grava `payments(PENDING)` ao consumir `PaymentRequested` e dá ack; um worker com lease (`FOR UPDATE SKIP LOCKED`) chama o provedor simulado fora de transação e publica `PaymentApproved`/`PaymentFailed` pelo outbox. Aprovação tardia é estornada ao consumir `RefundRequested` (`refunds.payment_id UNIQUE`), publicando `PaymentRefunded`. Tudo isso já está implementado e testado; este RFC registra o contrato de comportamento e as pendências.

## 2. Motivation

É o componente que sustenta R5 (dependência instável configurável por env), F1 (compensação) e F7 (resposta tardia), e o alvo da autoescala (F8). O README ainda diz "estorno chega na F3", embora o código e os testes de estorno já existam (commit `7159f62`), então quem lê a documentação subestima o estado real.

## 3. Current state

Verificado em 19/11 em `services/payment-service`:

| Item | Estado | Evidência |
|---|---|---|
| `PaymentRequested` → `payments(order_id UNIQUE, PENDING)` + `processed_messages`, `ON CONFLICT DO NOTHING`, ack depois do commit | ✅ | `PaymentRequestedHandler`, `ConsumerStoresPendingPaymentOnce` |
| Worker: `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) SET lease_until`, charge fora de TX, `UPDATE … WHERE status='PENDING'` + outbox na mesma TX | ✅ | `PaymentWorker`, `TwoWorkersChargeOnce` |
| Provedor simulado: `PAYMENT_LATENCY_MS ± JITTER`, `PAYMENT_FAILURE_RATE`, idempotente por `paymentId` via `provider_charges`, corte por `CancellationToken` | ✅ | `SimulatedPaymentProvider`, 5 testes unitários |
| Timeout duro → `FAILED(PROVIDER_TIMEOUT)`; erro do provedor → `FAILED(PROVIDER_ERROR)`; sem retry de cobrança | ✅ | `PaymentWorker`, `ProviderTimeoutFailsThePayment` |
| `RefundRequested` → `refunds` (valor e moeda lidos do pagamento, não da mensagem) + `PaymentRefunded` na mesma TX; desconhecido, não aprovado ou repetido → `StaleEventException` | ✅ | `RefundRequestedHandler`, `RefundFlowTests` |
| Migrations V0001–V0005 (`payments`, `provider_charges`, `refunds`, `outbox`, `processed_messages`), índice parcial `payments_pending_idx` | ✅ | `services/payment-service/migrations/` |
| Réplicas ×2 e alvo do autoscaler | ✅ | `compose.yaml`, `scripts/check-replicas.sh` |
| `correlationId` do worker | ⚠️ | `new Claimed(..., Guid.NewGuid().ToString())`: o `PaymentApproved`/`PaymentFailed` sai com **correlationId novo**, não o do `PaymentRequested` (o consumidor não o grava em `payments`) |
| Validação cruzada `PAYMENT_WORKER_LEASE_MS > PAYMENT_PROVIDER_TIMEOUT_MS` | ❌ | RFC 0003 §5.1 exige; `AppConfig` valida cada variável isolada |
| README: estado, env `PAYMENT_*` | ⚠️ | tabela de env do README omite `PAYMENT_*` (R5), e o estado diz que o estorno chega na F3 |
| Tabelas `payments.failure_reason` | `DECLINED`, `PROVIDER_ERROR`, `PROVIDER_TIMEOUT` | iguais ao enum de `PaymentFailed.reason` ✅ |

Fato do F7: `scripts/f7-late-response.sh` sobe o payment com `PAYMENT_LATENCY_MS=20000` e `PAYMENT_PROVIDER_TIMEOUT_MS=30000`, e o lease padrão é 30000, ou seja, **igual** ao timeout. Funciona porque a cobrança termina aos ~20 s, mas a regra do RFC 0003 (lease > timeout) está violada no cenário e a validação cruzada acusaria o próprio script.

## 4. Goals / Non-goals

**Goals**

- R5: latência, jitter, taxa de falha e timeout mudáveis só por env, validados no boot.
- Uma única cobrança por pedido, mesmo com 3 réplicas, worker morto no meio e reentrega.
- Um único estorno por pagamento aprovado.
- Rastreabilidade: o `correlationId` do pedido atravessa o worker.

**Non-goals**

- Retry de cobrança (RFC 0003 Decisão 3: falha vira compensação, aprovação tardia vira estorno).
- Estorno parcial, múltiplos meios de pagamento, API HTTP de pagamento (o serviço é só eventos).
- Provedor real (o simulado é o objeto da avaliação).

## 5. Design

### 5.1 Máquina de estados do pagamento

```
(nenhum) ── PaymentRequested ──> PENDING ──(worker, lease)──> APPROVED ── RefundRequested ──> (refunds row)
                                    └────────────────────────> FAILED(reason)
```

`APPROVED` e `FAILED` são finais; o estorno é uma linha em `refunds`, não um status. Todas as transições são `UPDATE … WHERE status = 'PENDING'`; zero linhas significa que outro worker concluiu depois de o lease expirar (retorna sem publicar nada).

### 5.2 Consumidor e worker

Como em [RFC 0003 §5.6](0003-failure-handling-strategy.md): o consumidor só grava e dá ack; a chamada lenta **não** segura a mensagem nem estoura o visibility timeout. O worker reivindica um `PENDING` com lease (`lease_until = now() + PAYMENT_WORKER_LEASE_MS`), chama `provider.ChargeAsync(paymentId, amount, ct)` com `ct` cancelado após `PAYMENT_PROVIDER_TIMEOUT_MS`, e conclui numa TX (status + outbox). Se o processo morrer durante a chamada, o lease expira, outro worker reivindica, e o `provider_charges` devolve o mesmo resultado sem nova espera (idempotência por `paymentId`).

### 5.3 Estorno

`RefundRequested` é consumido pelo handler de §3. Condições: o pagamento existe **e** está `APPROVED`. O `RefundRequested` só é publicado pelo order depois de receber `PaymentApproved`, então a corrida "estorno antes da aprovação" não ocorre em fluxo normal; se ocorresse, a mensagem seria `Stale` (descartada com ack) e o dinheiro ficaria retido. Registrado em Riscos.

### 5.4 `correlationId` no worker (ajuste proposto)

Gravar o `correlationId` do envelope de `PaymentRequested` junto do pagamento (`payments.correlation_id uuid NULL`, migration `V0006`) e usá-lo no `Claimed`. Alternativa sem migration: deixar como está e aceitar traço solto (o `traceparent` já liga os spans). Ver Decisão 3.

### 5.5 Validação de configuração (ajuste proposto)

No `AppConfig`: `PAYMENT_WORKER_LEASE_MS > PAYMENT_PROVIDER_TIMEOUT_MS` ou o boot falha nomeando as duas variáveis (mesmo padrão da validação TTL × deadline do order/inventory). Como o F7 usa timeout 30000, o script passa a definir também `PAYMENT_WORKER_LEASE_MS` (por exemplo timeout + 10000), em tarefa separada do plano e **só com o aceite do dono dos scripts** (Matheus).

### 5.9 Eventos

| Publica (`ticketpro-payments`) | Consome (`ticketpro-payment-service`) |
|---|---|
| `PaymentApproved`, `PaymentFailed`, `PaymentRefunded` (v1) | `PaymentRequested`, `RefundRequested` (v1) |

### 5.6 Invariantes

1. `payments.order_id UNIQUE`: um pedido, um pagamento.
2. `refunds.payment_id UNIQUE`: um pagamento, no máximo um estorno, e só se `APPROVED`.
3. O valor do estorno é o capturado, não o pedido na mensagem.
4. Evento publicado só pelo outbox, na TX da mudança de estado; ack só depois do commit.
5. Nunca duas cobranças ao provedor para o mesmo `paymentId` (provedor idempotente + `status='PENDING'` guardado).

### 5.7 Falhas

| Falha | Comportamento |
|---|---|
| Worker morre no meio da cobrança | lease expira; outro worker retoma; mesma resposta por `provider_charges` |
| Provedor lento além do timeout | `FAILED(PROVIDER_TIMEOUT)` e **sem retry**; o order cancela; se o provedor aprovasse depois, nada acontece (já `FAILED`) |
| Aprovação depois do deadline do order (F7) | order publica `RefundRequested` → `PaymentRefunded` |
| `PaymentRequested` duplicado ×5 | 1 linha em `payments` |
| `RefundRequested` duplicado | `StaleEventException`, sem segundo estorno |
| Broker fora | outbox acumula, relay em backoff; `/health/ready` degradado (200) |
| Banco fora | `/health/ready` 503; consumidor devolve a mensagem |

### 5.8 Observabilidade e configuração

Logs JSON (`payment_completed orderId status reason`); sem dados de cartão (não existem). Configuração por env, validada: `PAYMENT_LATENCY_MS` (500), `PAYMENT_LATENCY_JITTER_MS` (300), `PAYMENT_FAILURE_RATE` (0.1), `PAYMENT_PROVIDER_TIMEOUT_MS` (8000), `PAYMENT_WORKER_LEASE_MS` (30000), `PAYMENT_WORKER_POLL_MS` (200), `CONSUMER_CONCURRENCY`, `OUTBOX_*`. Spans de consumer/producer vêm do `Platform`.

## 6. Tests

Já cobertos: consumidor idempotente, worker aprova, timeout, duas réplicas cobram uma vez, estorno único, valor capturado, estorno desconhecido. A acrescentar: validação lease × timeout (`AppConfigTests`), `correlationId` propagado (se Decisão 3), `RefundRequested` de pagamento ainda `PENDING` (comportamento definido), e F1/F7/F8 pelos scripts (`f1-compensation.sh`, `f7-late-response.sh`, `f8-scale.sh`) com a stack completa, **que dependem do order Java** (plano do PR #13).

## 7. Docs

Corrigir o README (estado, estorno pronto, tabela de env com `PAYMENT_*`); runbook já cobre F1/F7.

## 8. Out of scope

Retry de cobrança, provedor real, estorno parcial, limpeza de `provider_charges`/`outbox`/`processed_messages` (retenção sem dono, ver visão geral).

## 9. Risks

- **Dependência do order Java:** F1, F7 e F8 só fecham quando o order consumir `PaymentApproved`/`PaymentFailed`/`PaymentRefunded` (T10/T11 do PR #13). O payment não tem como provar isso sozinho.
- **Estorno antes da aprovação** descartado como `Stale` deixaria dinheiro retido. Só ocorreria se o order publicasse `RefundRequested` sem ter visto `PaymentApproved` (não faz). Mantido, com teste que fixa o comportamento.
- **Lease = timeout no F7:** pequena janela de dupla reivindicação se a latência chegasse ao timeout; a idempotência do provedor e o `status='PENDING'` evitam cobrança dupla. Corrigir com §5.5.
- **Pagamento `PENDING` que nunca completa** (provedor falhando sempre por exceção): cada ciclo vira `PROVIDER_ERROR`, que é final, então não há laço; o risco é de arquivo, não de loop.

## 10. Unresolved questions

- Vale a migration `V0006` só para o `correlationId` (Decisão 3)? Dono: Gabriel.
- Quem altera o `f7-late-response.sh` para definir o lease (Matheus, dono dos scripts)?

## 11. Decisions

| # | Grade | Decision |
|---|---|---|
| 1 | `LOCKED` | Sem retry automático de cobrança; falha ou timeout compensa e aprovação tardia estorna (RFC 0003, Decisão 3). |
| 2 | `ASSUMED` | `PAYMENT_WORKER_LEASE_MS > PAYMENT_PROVIDER_TIMEOUT_MS` passa a ser validado no boot (§5.5). |
| 3 | `OPEN` | **Substituída pela #6.** `correlationId` do pedido no worker: migration `V0006` com `payments.correlation_id` ou aceitar trace solto. Decidir ao implementar; custo de uma coluna nullable é mínimo. |
| 4 | `ASSUMED` | `RefundRequested` para pagamento não `APPROVED` continua `Stale` (ack sem efeito), com teste que fixa o comportamento. |
| 5 | `ASSUMED` | O estorno simulado nunca falha (RFC 0003 §5.6). |
| 6 | `ASSUMED` | Resolve a #3: `V0006` adiciona `payments.correlation_id` (nullable); o consumidor grava o do `PaymentRequested` e o worker o usa no `PaymentApproved`/`PaymentFailed`, com UUID novo só para linhas anteriores à migration. Ver commit `2b671a0` (PR #22). |
| 7 | `ASSUMED` | Responde à segunda questão da §10: o `f7-late-response.sh` passa `PAYMENT_WORKER_LEASE_MS = latência + 20000` (timeout = latência + 10000), senão a validação da #2 impede o payment de subir no cenário. O mesmo vale para o passo 1 do F7 na collection do Postman. |

## 12. Phasing

- **P1:** README + validação de config (sem risco).
- **P2:** `correlationId` (Decisão 3) e o teste de estorno em `PENDING`.
- **P3:** verificação F1/F7/F8 assim que o order Java estiver verde.
