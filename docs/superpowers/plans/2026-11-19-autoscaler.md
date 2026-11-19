# Autoscaler (Go): plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (ou subagent-driven-development) para executar tarefa a tarefa, e superpowers:test-driven-development em cada uma. **Este plano só descreve trabalho: nenhum código, migration, teste ou script foi escrito ao produzi-lo.**

**Goal:** Manter o `autoscaler` (**implementado**, bônus F4 nº 5) demonstrável e honesto: decidir o sinal de carga, corrigir a documentação e ensaiar a demo, até o **freeze de ter 24/11 às 12h**. É o **primeiro bônus a cortar**.

**Architecture:** Sem mudança planejada de código ([RFC 0012](../../rfc/0012-design-do-autoscaler-em-go.md)); a única decisão aberta é o sinal usado (RFC 0012 §5.2).

**Tech Stack:** Go 1.25, AWS SDK v2 (SQS), Docker Engine API sobre unix socket.

**Spec:** [RFC 0012](../../rfc/0012-design-do-autoscaler-em-go.md); [ADR-0015](../../adr/0015-queue-based-autoscaler.md); [RFC 0003 §5.8](../../rfc/0003-failure-handling-strategy.md); [arquitetura §9](../../arquitetura.md).

## Global Constraints

- **Branch:** `chore/autoscaler-<descrição>`; nunca na `main`; sem push sem pedir; sem worktree.
- **ADR aceito não se edita:** mudar o sinal para algo que consulte o banco do payment ou crie endpoint novo exige **ADR novo** que substitua o 0015 (create-adr).
- **Env, não hardcode.**
- **Definition of Done:** `cd services/autoscaler && make lint typecheck test` verde; `scripts/demo-autoscale.sh` exit 0; README atualizado.
- **Ownership:** Pedro.

## Escopo e corte até o freeze

| Faixa | Tarefas | Se o prazo apertar |
|---|---|---|
| **Must** | S01 (README + link) | Barato. |
| **Should** | S02 (decidir o sinal), S03 (ensaio da demo) | Cortar a demo inteira se F1–F8 não estiverem verdes até dom 22/11 12h (roadmap F4: bônus 5 é o primeiro a sair). |
| **Could** | S04 (alvo do inventory) | Cortar primeiro. |

**Linha de corte:** o roadmap manda cortar este bônus primeiro. Sem ele, o F8 continua com `docker compose up --scale payment-service=3`.

### Cronograma

| Dia | Pedro | Checkpoint |
|---|---|---|
| sex 20/11 | S01 | link e README corrigidos |
| dom 22/11 (após a linha de corte) | S02 | decisão registrada |
| seg 23/11 | S03, S04 | `demo-autoscale.sh` exit 0 |
| ter 24/11 até 12h | freeze | ensaio |

### Dependências

```
S01 ─ S02 ─ S03
        └ S04 (opcional)
S03 depende de payment (pronto) e do order Java só indiretamente (demo injeta PaymentRequested)
```

## Mapa requisito/cenário → tarefa → script

| Item | Tarefas | Script |
|---|---|---|
| Bônus autoescala (ADR-0015) | S02, S03 | `demo-autoscale.sh` |
| F8 escala | S03 | `f8-scale.sh` (para o autoscaler durante o `--scale 3`) |
| R4 | indireta | `check-replicas.sh` |

## Riscos

| # | Risco | Prob. | Mitigação |
|---|---|---|---|
| 1 | Fila do payment quase vazia na carga real (o consumidor só grava e dá ack) | Alta | S02; demo de mecanismo declarada |
| 2 | Docker socket montado é controle total do host | Baixa (demo) | Declarado no ADR-0015 |
| 3 | `compose up` durante a demo desfaz a escala | Média | Documentado no README |
| 4 | Ensaio sem memória para 5 réplicas | Média | Medir no ensaio (risco 5 do plano do PR #13) |

## Review Focus

1. Cada decisão aparece como `scale_decision` no log.
2. Nenhuma réplica órfã após a demo.
3. Qualquer mudança do sinal tem ADR.

---

## Tarefas

### S01: README e link do ADR

- **Owner:** Pedro. **Depende de:** nada.
- **Passos:** [ ] Corrigir o link para `../../docs/adr/0015-queue-based-autoscaler.md` (hoje cita `0015-queue-depth-autoscaling-docker-api.md`, inexistente). [ ] Linkar o RFC 0012; documentar o que a demo prova.
- **Pronto quando:** links resolvem. **Valida:** verificação de links. **Mapeia:** documentação.

### S02 (Should): Decidir o sinal de carga

- **Owner:** Pedro com o grupo. **Depende de:** S01.
- **Passos:** [ ] Escolher entre as opções 1–4 do [RFC 0012 §5.2](../../rfc/0012-design-do-autoscaler-em-go.md). [ ] Opção 2 ou 3: **escrever ADR novo** (substitui o 0015) antes de qualquer código; opção 1 ou 4: nenhuma mudança de código. [ ] Registrar a escolha como linha nova no RFC 0012.
- **Pronto quando:** decisão registrada (e ADR, se for o caso). **Valida:** n/a. **Mapeia:** bônus autoescala.

### S03: Ensaio da demo

- **Owner:** Pedro. **Depende de:** S02.
- **Passos:** [ ] `scripts/demo-autoscale.sh` em stack limpa, duas vezes seguidas, com medição de tempo e de memória. [ ] Conferir que não restam réplicas órfãs (`docker compose ps`). [ ] Atualizar `docs/runbook.md` (bônus 5) se o roteiro mudou.
- **Pronto quando:** exit 0 nas duas execuções. **Valida:** `scripts/demo-autoscale.sh`; `scripts/f8-scale.sh`. **Mapeia:** bônus, F8.

### S04 (Could): Alvo do inventory

- **Owner:** Pedro. **Depende de:** S02 (opção 4).
- **Passos:** [ ] `AUTOSCALER_TARGETS=payment-service=ticketpro-payment-service,inventory-service=ticketpro-inventory-service` (só env); verificar com a demo que o inventory Java sobe/desce. [ ] Aviso do README sobre a remoção sequencial.
- **Pronto quando:** demo com dois alvos exit 0. **Valida:** `scripts/demo-autoscale.sh` adaptado. **Mapeia:** bônus.
