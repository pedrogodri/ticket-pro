# Payment-service (C#): plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (ou subagent-driven-development) para executar tarefa a tarefa, e superpowers:test-driven-development em cada uma. **Este plano só descreve trabalho: nenhum código, migration, teste ou script foi escrito ao produzi-lo.**

**Goal:** Manter o `payment-service` (consumidor, worker com lease, provedor simulado, estorno: **já prontos**) alinhado a R5/F1/F7/F8: validação lease × timeout, `correlationId`, README honesto e verificação F1/F7/F8 assim que o order Java existir, até o **freeze de ter 24/11 às 12h**.

**Architecture:** Nenhuma camada nova ([RFC 0009](../../rfc/0009-design-do-payment-service-em-csharp.md)); ajustes pequenos em `AppConfig`, no worker e (opcional) numa migration.

**Tech Stack:** C# / .NET 10, Npgsql, xUnit + Testcontainers; `Platform/` vendorizada (não editar).

**Spec:** [RFC 0009](../../rfc/0009-design-do-payment-service-em-csharp.md); [RFC 0003 §5.1, §5.6, §5.7](../../rfc/0003-failure-handling-strategy.md); [arquitetura §6](../../arquitetura.md); [padrões](../../padroes.md) §2.3, §11.

## Global Constraints

- **Branch:** `feat/payment-service-<descrição>`; nunca na `main`; sem push sem pedir; sem worktree. Commits `feat(payment-service): ...`.
- **Nunca editar** `Platform/` nem `contracts/`.
- **Migrations:** V0001–V0005 não se editam; `V0006` só com a Decisão 3 do RFC 0009.
- **Sem retry de cobrança** (RFC 0003 Decisão 3): nenhuma tarefa pode introduzir.
- **Definition of Done:** `cd services/payment-service && make lint typecheck test` verde (integração incluída); scripts com exit 0; README atualizado.
- **Ownership:** Gabriel. `scripts/f7-late-response.sh` pertence a Matheus: P03 só com o aceite dele.

## Escopo e corte até o freeze

| Faixa | Tarefas | Se o prazo apertar |
|---|---|---|
| **Must** | P01 (README), P02 (validação de config), P04 (verificar F1/F7/F8) | P04 não depende do payment, mas é a prova de R5; nunca cortar. |
| **Should** | P03 (env do F7), P05 (`correlationId`) | Cortar P05 antes de P03; P03 elimina a violação lease = timeout no cenário. |
| **Could** | P06 (teste de estorno com pagamento `PENDING`) | Cortar primeiro. |

**Linha de corte:** P01/P02 até sex 20/11; P04 assim que `happy-path.sh` do order estiver verde (previsto sáb 21/11 no plano do PR #13); dom 22/11 12h é o limite.

### Cronograma

| Dia | Gabriel | Checkpoint |
|---|---|---|
| qui 19/11 | P01 | README corrigido |
| sex 20/11 | P02, P03 (com Matheus) | `AppConfigTests` verde; F7 com lease explícito |
| sáb 21/11 | P04 (F1) | `f1-compensation.sh` exit 0 |
| dom 22/11 até 12h | P04 (F7, F8), P05 | `f7`, `f8` exit 0 |
| seg–ter | P06, ensaio | freeze ter 12h |

### Dependências

```
P01 ─ P02 ─ P03 ─┐
                 ├ P04 (depende de T10/T11 do PR #13: order consome PaymentApproved/Failed/Refunded)
P05, P06 independentes
```

## Mapa requisito/cenário → tarefa → script

| Item | Tarefas | Script |
|---|---|---|
| **R5** dependência instável por env | P01 (documenta `PAYMENT_*`), P02 | `f1-compensation.sh`, `f7-late-response.sh` |
| **F1** compensação | P04 | `f1-compensation.sh` |
| **F7** resposta tardia | P03, P04 | `f7-late-response.sh` |
| **F8** escala ×3 | P04 | `f8-scale.sh`, `demo-autoscale.sh` |
| **R1** | já coberto (duplicata, refund único) | `f3-duplicate.sh` (com o order) |

## Riscos

| # | Risco | Prob. | Mitigação |
|---|---|---|---|
| 1 | **F1/F7/F8 dependem do order Java** (hoje só scaffold) | Alta | Fora do controle do payment; P04 só começa depois de T10/T11 do PR #13 |
| 2 | Lease = timeout no F7 | Média | P02 + P03 |
| 3 | Estorno antes da aprovação descartado como `Stale` | Baixa | P06 fixa o comportamento; fluxo normal não ocorre |
| 4 | Mudar `f7-late-response.sh` conflita com o dono dos scripts | Baixa | Combinar com Matheus (P03) |

## Review Focus

1. Uma cobrança por pedido com 3 réplicas (`TwoWorkersChargeOnce`, `f8-scale.sh`).
2. Estorno com o valor capturado.
3. A validação lease × timeout nomeia as duas variáveis.

---

## Tarefas

### P01: README honesto

- **Owner:** Gabriel. **Depende de:** nada.
- **Files:** `services/payment-service/README.md`.
- **Passos:** [ ] "Estado": estorno pronto. [ ] Tabela de env com `PAYMENT_LATENCY_MS`, `PAYMENT_LATENCY_JITTER_MS`, `PAYMENT_FAILURE_RATE`, `PAYMENT_PROVIDER_TIMEOUT_MS`, `PAYMENT_WORKER_LEASE_MS`, `PAYMENT_WORKER_POLL_MS`. [ ] Tabela `payments`/`provider_charges`/`refunds`. [ ] Link do RFC 0009.
- **Pronto quando:** README coerente com `.env.example` e [RFC 0009 §3](../../rfc/0009-design-do-payment-service-em-csharp.md).
- **Valida:** `scripts/check-autonomy.sh services/payment-service`. **Mapeia:** R5 (evidência para a banca).

### P02: Validação lease × timeout

- **Owner:** Gabriel. **Depende de:** P01.
- **Files:** `App/AppConfig.cs`, `tests/.../AppConfigTests.cs`.
- **Passos:** [ ] Teste: lease ≤ timeout falha no boot citando `PAYMENT_WORKER_LEASE_MS` e `PAYMENT_PROVIDER_TIMEOUT_MS`; padrões (30000 > 8000) passam. [ ] Implementar.
- **Pronto quando:** testes verdes; serviço não sobe com valores inconsistentes.
- **Valida:** `make test`. **Mapeia:** RFC 0003 §5.1.

### P03: Env do F7 com lease explícito

- **Owner:** Gabriel e Matheus (dono do script). **Depende de:** P02.
- **Files:** `scripts/f7-late-response.sh` (apenas `set_payment ... PAYMENT_WORKER_LEASE_MS=$((LATENCY_MS + 20000))`).
- **Passos:** [ ] Ajustar o script para respeitar a nova validação. [ ] Rodar o F7 inteiro.
- **Pronto quando:** `f7-late-response.sh` exit 0 (com o order Java). **Valida:** `scripts/f7-late-response.sh`. **Mapeia:** F7.

### P04: Verificação F1/F7/F8 com o order Java

- **Owner:** Gabriel com Matheus. **Depende de:** T10/T11 do [plano do PR #13](2026-11-19-java-order-inventory.md); P03.
- **Passos:** [ ] `scripts/f1-compensation.sh`, `f7-late-response.sh`, `f8-scale.sh` em stack limpa. [ ] Relatar divergências ao dono do order sem editar a pasta dele.
- **Pronto quando:** os três com exit 0. **Valida:** os três scripts. **Mapeia:** F1, F7, F8, R5.

### P05 (Should): `correlationId` do pedido no worker

- **Owner:** Gabriel. **Depende de:** P01 e da Decisão 3 do RFC 0009.
- **Passos:** [ ] `V0006__add_payments_correlation_id.sql` (coluna nullable). [ ] Consumidor grava, worker usa. [ ] Teste: o `PaymentApproved` sai com o `correlationId` do `PaymentRequested`.
- **Pronto quando:** teste verde; trace da saga encadeado no log. **Valida:** `make test`. **Mapeia:** observabilidade.

### P06 (Could): Estorno de pagamento ainda `PENDING`

- **Owner:** Gabriel. **Depende de:** nada.
- **Passos:** [ ] Teste que fixa o comportamento (`Stale`, sem `refunds`). 
- **Pronto quando:** teste verde. **Valida:** `make test`. **Mapeia:** RFC 0009 Decisão 4.
