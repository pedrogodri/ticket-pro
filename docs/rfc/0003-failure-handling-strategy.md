# RFC 0003 — Estratégia de falhas

- **Status:** 📝 Draft (design fechado — implementação ao longo das fases 1–3 do roadmap)
- **Scope:** Retry, backoff, timeouts, visibility timeout, DLQ, mensagem venenosa, reconexão ao broker, health checks e compensações em cada camada (gateway, HTTP interno, outbox, consumidores, provedor de pagamento). Liga cada mecanismo aos cenários F1–F8. Não cobre formato do envelope (RFC 0001) nem autenticação (RFC 0002).
- **Related:** [ADR-0003](../adr/0003-orchestrated-saga-in-order-service.md), [ADR-0005](../adr/0005-transactional-outbox.md), [ADR-0006](../adr/0006-idempotent-consumers-processed-messages.md), [ADR-0007](../adr/0007-conditional-update-prevents-overselling.md), [arquitetura §6, §9](../arquitetura.md)

---

## 1. Summary

As falhas são tratadas em camadas, cada uma com um dono:

- **Outbox:** backoff exponencial com jitter até o broker voltar (R6).
- **Consumidor:** ack só depois do commit, reentrega pelo SQS com visibility backoff, até 3 tentativas e depois DLQ.
- **Payment:** provedor instável com timeout duro, chamado fora do ciclo da mensagem.
- **Order:** deadline de pagamento e compensação.
- **Inventory:** TTL de reserva como rede de segurança.

Todos os valores vêm de variáveis de ambiente.

## 2. Motivation

Os cenários F1–F8 são a avaliação prática. Cada um precisa de um mecanismo explícito, configurável e observável em log/trace, e sem mecanismo duplicado entre camadas (retry em cima de retry gera tempestade).

## 3. Current state

Sem código. Design de saga e outbox fechado nos ADRs.

## 4. Goals / Non-goals

**Goals**

- Nenhuma perda de mensagem com o broker fora do ar ou com o consumidor morto.
- Nenhum efeito duplicado com reentrega.
- Mensagem venenosa isolada sem travar a fila.
- Compensação sempre convergente, inclusive com resposta tardia.

**Non-goals**

- Exactly-once de transporte: at-least-once + idempotência é o modelo.
- Alta disponibilidade do Postgres ou do MiniStack (um nó cada na demo).

## 5. Design

### 5.1 Tabela de timeouts e limites (valores padrão)

| Variável | Padrão | Dono | Restrição |
|---|---|---|---|
| `GATEWAY_UPSTREAM_TIMEOUT_MS` | 5000 | gateway | — |
| `HTTP_CLIENT_TIMEOUT_MS` | 3000 | todos | — |
| `SQS_WAIT_TIME_S` (long polling) | 10 | consumidores | ≤ 20 |
| `SQS_VISIBILITY_TIMEOUT_S` | 30 | Terraform/consumidores | > p99 do processamento de uma mensagem |
| `SQS_MAX_RECEIVE_COUNT` | 3 | Terraform | — |
| `CONSUMER_CONCURRENCY` | 4 | consumidores | mensagens em paralelo por réplica |
| `OUTBOX_POLL_INTERVAL_MS` | 200 | produtores | — |
| `OUTBOX_BATCH_SIZE` | 50 | produtores | — |
| `OUTBOX_MAX_BACKOFF_MS` | 30000 | produtores | — |
| `PAYMENT_LATENCY_MS` | 500 | payment | latência média do provedor simulado |
| `PAYMENT_LATENCY_JITTER_MS` | 300 | payment | — |
| `PAYMENT_FAILURE_RATE` | 0.1 | payment | 0.0–1.0 |
| `PAYMENT_PROVIDER_TIMEOUT_MS` | 8000 | payment | corta a chamada → `PaymentFailed(PROVIDER_TIMEOUT)` |
| `PAYMENT_WORKER_LEASE_MS` | 30000 | payment | > `PAYMENT_PROVIDER_TIMEOUT_MS` |
| `ORDER_PAYMENT_TIMEOUT_MS` | 15000 | order | deadline a partir de `AWAITING_PAYMENT` |
| `ORDER_DEADLINE_SCAN_INTERVAL_MS` | 1000 | order | — |
| `RESERVATION_TTL_MS` | 60000 | inventory | ≥ `ORDER_PAYMENT_TIMEOUT_MS` + `RESERVATION_SAFETY_MARGIN_MS` |
| `RESERVATION_SAFETY_MARGIN_MS` | 30000 | order + inventory | os dois serviços validam no boot e recusam subir se violado |

Para o F7 (resposta tardia) é preciso `PAYMENT_PROVIDER_TIMEOUT_MS > ORDER_PAYMENT_TIMEOUT_MS`, por exemplo `PAYMENT_LATENCY_MS=20000` e `PAYMENT_PROVIDER_TIMEOUT_MS=30000`. O order cancela em 15 s, a aprovação chega aos ~20 s e dispara o estorno. O roteiro do F7 sobrescreve as envs.

### 5.2 Backoff padrão

O backoff exponencial com *full jitter*, igual em todas as linguagens e implementado em `shared/*/retry` ou equivalente, é:

```
delay(n) = random(0, min(cap, base * 2^n))     base = 200 ms, cap = 30 s
```

### 5.3 Produtor: outbox e relay (R6, F6)

- Erro de publish (conexão recusada, timeout, 5xx do MiniStack): `attempts += 1` e `next_attempt_at = now + delay(attempts)`. O relay **nunca** descarta, porque não há limite de tentativas no outbox.
- Com o broker fora, `/health/ready` reporta `broker: degraded`, mas o serviço continua **aceitando requisições**: o `POST /orders` grava no outbox e responde 202. O `/health/live` continua OK, para o Docker não reiniciar o container em loop.
- O relay loga uma linha por transição de estado do broker (up → down → up), e não uma por tentativa.
- **F6:** `docker pause ministack` por 30 s → os pedidos ficam em `PENDING` e o outbox cresce. Depois `docker unpause` → o relay drena e a saga completa sem intervenção.

### 5.4 Consumidor: processamento, retry e DLQ (R1, R4, F2, F3, F4)

Loop por réplica:

1. `ReceiveMessage` (long polling, `MaxNumberOfMessages = CONSUMER_CONCURRENCY`, atributo `ApproximateReceiveCount`).
2. Desembrulha (SNS/raw), valida envelope e payload (RFC 0001).
3. Classifica o resultado:

| Resultado | Ação | Ack? |
|---|---|---|
| Sucesso | commit (efeito + `processed_messages` + outbox) | sim, `DeleteMessage` após o commit |
| Duplicata (`processed_messages` conflita) | rollback, log `duplicate=true` | sim |
| Transição inválida (evento atrasado, pedido já final) | registra em `processed_messages`, log `stale_event` | sim |
| **Venenosa** (JSON inválido, schema inválido, `type`/`version` não suportados) | log `poison=true` com `messageId` | **não**, `ChangeMessageVisibility(0)` para chegar logo ao `maxReceiveCount` → DLQ |
| **Transitória** (banco indisponível, deadlock, timeout) | rollback | **não**, `ChangeMessageVisibility(delay(receiveCount))` |

4. Com `ApproximateReceiveCount > SQS_MAX_RECEIVE_COUNT`, o redrive policy do SQS move a mensagem para `ticketpro-<svc>-dlq`. A fila principal continua fluindo.

- **F2:** `docker kill` numa réplica do inventory com mensagem em voo → nada foi commitado nem houve ack → depois do visibility timeout a outra réplica recebe → efeito único.
- **F3:** o script `scripts/f3-duplicate.sh` publica o **mesmo envelope** (mesmo `messageId`) 5× → 1 linha de efeito e 4 logs `duplicate=true`.
- **F4:** o script publica `OrderCreated` com payload inválido → 3 recebimentos → DLQ. Ferramenta: `scripts/dlq.sh list|show|redrive <svc>`.
- **Proibido:** dar ack antes do commit, fazer retry infinito em memória ou capturar exceção e dar ack para "limpar a fila".

### 5.5 Reconexão ao broker (consumidor) (R6)

Erros de `ReceiveMessage` (rede, broker pausado) não derrubam o processo. O loop espera `delay(n)`, tenta de novo e zera `n` no primeiro sucesso. O `/health/ready` reflete o estado. Os SDKs AWS têm retry próprio, que é **reduzido a 1 tentativa** (`max_attempts=1` / equivalente) para que o backoff seja o nosso e não haja retry sobre retry.

### 5.6 Dependência instável: payment (R5, F1, F7)

```
consumidor PaymentRequested ── TX: payments(order_id UNIQUE, PENDING) + processed_messages ──> ack
worker ── SELECT … WHERE status='PENDING' AND (lease_until IS NULL OR lease_until < now())
          FOR UPDATE SKIP LOCKED → lease_until = now + LEASE
       ── provider.charge(paymentId, amount)   # latência + falha + timeout
       ── TX: status = APPROVED|FAILED + outbox PaymentApproved|PaymentFailed
```

- O provedor simulado é um componente **dentro** do payment-service, atrás de uma interface `IPaymentProvider`. Ele aplica `Task.Delay(latência ± jitter)` e falha com probabilidade `PAYMENT_FAILURE_RATE`. É idempotente por `paymentId`: uma segunda chamada devolve o mesmo resultado, guardado em `provider_charges`.
- O timeout é duro (`PAYMENT_PROVIDER_TIMEOUT_MS`) via `CancellationToken`/Polly e resulta em `FAILED(PROVIDER_TIMEOUT)`. **Não há retry automático** de cobrança: falhou, o pedido é compensado. Isso evita cobrança dupla e deixa o F1 determinístico.
- Se o worker morrer no meio da chamada, o lease expira e outro worker retoma. A idempotência por `paymentId` garante uma única cobrança.
- **F1:** `PAYMENT_FAILURE_RATE=1` → `PaymentFailed` → `OrderCancelled(PAYMENT_FAILED)` → o inventory devolve o estoque. A demo mostra `available` antes e depois.
- **Estorno:** `RefundRequested` → `refunds(payment_id UNIQUE)` → `PaymentRefunded`. O estorno simulado não falha, porque é fora do escopo de instabilidade.

### 5.7 Compensações (saga)

| Gatilho | Quem detecta | Ação de compensação | Idempotência |
|---|---|---|---|
| `InventoryRejected` | order | `CANCELLED(OUT_OF_STOCK)`, nada a desfazer | transição guardada |
| `PaymentFailed` | order | `CANCELLED(PAYMENT_FAILED)` → `OrderCancelled` → inventory libera | `reservations` `PENDING → RELEASED` guardada |
| Deadline vencido | deadline scanner (order) | `CANCELLED(PAYMENT_TIMEOUT)` → libera | idem |
| `InventoryReservationExpired` | order | `CANCELLED(RESERVATION_EXPIRED)` se ainda não for final | transição guardada |
| `PaymentApproved` com pedido `CANCELLED` (F7) | order | `RefundRequested` → `PaymentRefunded` | `refunds.payment_id UNIQUE` |
| `PaymentApproved` depois do deadline com o pedido ainda `AWAITING_PAYMENT` | order | `CANCELLED(PAYMENT_TIMEOUT)` + `RefundRequested` na mesma TX | idem |
| `UserCreationFailed` | auth | remove a credencial + `TokenRevoked` | `processed_messages` |

### 5.8 Concorrência e escala (R2, R4, F5, F8)

- Réplicas padrão: order ×2, inventory ×2, payment ×2.
- **F5:** o script `scripts/f5-race.sh` cria um setor com 10 unidades (seed `F5`) e dispara 100 `POST /orders` concorrentes, com N usuários para não esbarrar no rate limit. Verificação: `SELECT available` = 0, 10 reservas `CONFIRMED`, 90 pedidos `CANCELLED(OUT_OF_STOCK)` e nenhum `CHECK` violado.
- **F8:** `docker compose up -d --scale payment-service=3` (ou o autoscaler com fila acumulada) → as 3 réplicas consomem a mesma fila, o tempo total cai e não há duplicidade.

### 5.9 Health checks

| Endpoint | Conteúdo | Uso |
|---|---|---|
| `GET /health/live` | `200` se o processo responde | `healthcheck` do compose (restart) |
| `GET /health/ready` | `200` com `{db: up, broker: up\|degraded}`; `503` se o **banco** estiver fora | `depends_on: condition: service_healthy` |

O broker degradado **não** deixa o serviço não-pronto, porque o outbox existe exatamente para isso.

## 6. Tests

- Unitários: classificação de erro (venenosa vs transitória), cálculo de backoff, upcaster.
- Integração (Testcontainers ou compose de teste): duplicata → efeito único; kill no meio → reentrega; payload inválido → DLQ; relay com broker fora → drena ao voltar.
- Cenários F1–F8 como scripts em `scripts/` com verificação automática (exit 0/1) usados no runbook.

## 7. Docs

O runbook de demonstração (`docs/runbook.md`, fase de docs final) traz um roteiro por cenário com comando, o que observar (log, trace no Jaeger, query) e o resultado esperado.

## 8. Out of scope

- Circuit breaker no provedor de pagamento. Com o timeout duro e sem retry, ele não muda o resultado da demo. Fica como porta de saída via Polly.
- Redrive automático da DLQ. É manual pelo `scripts/dlq.sh`, de propósito, para exigir análise humana.

## 9. Risks

- **Comportamento de `ChangeMessageVisibility` e do redrive no MiniStack** pode divergir da AWS. Mitigação: spike na fase 1. Se o redrive não funcionar, o consumidor move a mensagem para a DLQ explicitamente (`SendMessage` na DLQ + `DeleteMessage`) quando `receiveCount ≥ max`.
- **Visibility timeout curto demais** para algum handler causa reentrega concorrente. Mitigação: o handler mais lento (payment) não faz I/O lento no consumo (§5.6), e a idempotência cobre o restante.
- **Relógios entre containers:** todos usam o relógio do host, e a margem de 30 s cobre qualquer diferença.

## 10. Unresolved questions

- Suporte do MiniStack a redrive policy e `ApproximateReceiveCount`. Sai da spike da fase 1, com plano B descrito no §9.

## 11. Decisions

| # | Grade | Decision |
| --- | --- | --- |
| 1 | `LOCKED` | O ack (`DeleteMessage`) acontece somente após o commit da transação que contém efeito + `processed_messages` + outbox. |
| 2 | `LOCKED` | O retry de consumo é delegado ao SQS (não ack + visibility backoff), com DLQ após `maxReceiveCount=3`. Sem retry em memória. |
| 3 | `LOCKED` | A cobrança ao provedor não tem retry automático. Falha ou timeout vira compensação, e aprovação tardia vira estorno. |
| 4 | `LOCKED` | `RESERVATION_TTL_MS ≥ ORDER_PAYMENT_TIMEOUT_MS + RESERVATION_SAFETY_MARGIN_MS`, validado no boot. |
| 5 | `ASSUMED` | Backoff exponencial com full jitter (base 200 ms, cap 30 s) em outbox, reconexão e visibility. |
| 6 | `ASSUMED` | Venenosa usa `ChangeMessageVisibility(0)` para chegar rápido à DLQ pelo redrive nativo, em vez de mover manualmente. |
| 7 | `ASSUMED` | Retry interno dos SDKs AWS reduzido a 1 tentativa para evitar retry sobre retry. |
| 8 | `ASSUMED` | Broker fora não deixa o serviço não-pronto; banco fora deixa. |
| 9 | `OPEN` | Valores finais de visibility timeout e concorrência por réplica. Calibrar medindo o p99 dos handlers na fase de testes. |

## 12. Phasing

- **P1:** backoff compartilhado, relay de outbox, loop de consumo com classificação e DLQ, health checks.
- **P2:** worker de pagamento com lease, deadline scanner e expiração de reservas.
- **P3:** scripts F1–F8 com verificação automática e `scripts/dlq.sh`.
