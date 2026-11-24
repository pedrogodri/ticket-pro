# Notification-service (Go): plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (ou subagent-driven-development) para executar tarefa a tarefa, e superpowers:test-driven-development em cada uma. **Este plano só descreve trabalho: nenhum código, migration, teste ou script foi escrito ao produzi-lo.**

**Goal:** Manter o `notification-service` (**já completo**) verificável ponta a ponta: README, teste de integração com S3 e um script que prove comprovante e notificação, até o **freeze de ter 24/11 às 12h**.

**Architecture:** Nenhuma mudança de código planejada ([RFC 0011](../../rfc/0011-design-do-notification-service-em-go.md)); só verificação e documentação.

**Tech Stack:** Go 1.25, pgx, AWS SDK v2 (S3), `internal/platform` vendorizada (não editar).

**Spec:** [RFC 0011](../../rfc/0011-design-do-notification-service-em-go.md); [ADR-0006](../../adr/0006-idempotent-consumers-processed-messages.md); [arquitetura §6](../../arquitetura.md); [padrões](../../padroes.md) §2.1, §7, §11.

## Global Constraints

- **Branch:** `chore/notification-service-<descrição>`; nunca na `main`; sem push sem pedir; sem worktree. Commits `docs(notification-service): ...` / `test(notification-service): ...`.
- **Nunca editar** `internal/platform/` nem `contracts/`.
- **Sem migration nem contrato novo.**
- **Definition of Done:** `cd services/notification-service && make lint typecheck test test-integration` verde; script com exit 0; README atualizado.
- **Ownership:** Pedro e Matheus (roadmap). Matheus está ocupado com order/plataforma (PR #13); a tarefa N02 é de Pedro.

## Escopo e corte até o freeze

| Faixa | Tarefas | Se o prazo apertar |
|---|---|---|
| **Must** | N01 (README), N03 (verificação no `happy-path.sh`/script) | N03 prova S3 e "2+ consumidores"; nunca cortar. |
| **Should** | N02 (IT com MiniStack) | Cortar por último dentre as opcionais. |
| **Could** | — | — |

**Linha de corte:** N01 até sex 20/11; N03 junto com o happy path do order (sáb 21/11); dom 22/11 12h é o limite.

### Cronograma

| Dia | Pedro | Checkpoint |
|---|---|---|
| sex 20/11 | N01 | README correto |
| sáb 21/11 | N02, N03 | `notification-check.sh` verde; `happy-path.sh` confere o objeto |
| dom 22/11 | revisão | — |
| ter 24/11 até 12h | freeze | ensaio |

### Dependências

```
N01 ─ N02 ─ N03 ─(happy-path.sh depende de T07/T10 do PR #13 para o objeto real)
```

## Mapa requisito/cenário → tarefa → script

| Item | Tarefas | Script |
|---|---|---|
| ≥3 categorias de nuvem (S3) | N02, N03 | `verify-infra.sh`, `happy-path.sh` |
| Evento com 2+ consumidores | N03 | `happy-path.sh` (user **e** notification) |
| R1 (duplicata) | N02 (reentrega ×5) | `notification-check.sh` |

## Riscos

| # | Risco | Prob. | Mitigação |
|---|---|---|---|
| 1 | Sem `TicketsIssued` do inventory Java não há comprovante real | Alta (hoje) | N03 usa evento injetado; o happy path real fecha com T07 |
| 2 | S3 fora não aparece no readiness | Baixa | Documentado (RFC 0011 §9) |
| 3 | Pedro sobrecarregado | Média | Todas as tarefas são pequenas |

## Review Focus

1. Um objeto por pedido e uma linha por (pedido, tipo) sob duplicata.
2. O objeto não contém dados além do necessário.

---

## Tarefas

### N01: README

- **Owner:** Pedro. **Depende de:** nada.
- **Files:** `services/notification-service/README.md`.
- **Passos:** [ ] Remover "Por onde começar" desatualizado (F1/F2). [ ] Documentar o que "notificar" significa (RFC 0011 Decisão 2). [ ] Linkar o RFC 0011.
- **Pronto quando:** README coerente com [RFC 0011 §3](../../rfc/0011-design-do-notification-service-em-go.md). **Valida:** `scripts/check-autonomy.sh services/notification-service`. **Mapeia:** documentação.

### N02 (Should): IT contra o MiniStack

- **Owner:** Pedro. **Depende de:** N01.
- **Files:** `internal/receipts/receipts_integration_test.go` (ou novo arquivo `*_integration_test.go` com `-tags integration`).
- **Passos:** [ ] Subir MiniStack no teste (como nos ITs do `shared/go`), processar `TicketsIssued` ×5 e ler `receipts/{orderId}.json`; conferir conteúdo e **um** registro. [ ] `OrderCancelled` ×5 → 1 registro.
- **Pronto quando:** `make test-integration` verde. **Valida:** `make test-integration`. **Mapeia:** R1, S3.

### N03: `scripts/notification-check.sh`

- **Owner:** Pedro. **Depende de:** N01.
- **Passos:** [ ] Publicar `TicketsIssued` e `OrderCancelled` de exemplo (`contracts/examples`) no tópico `ticketpro-inventory`/`ticketpro-orders` com `scripts/lib/aws.sh`. [ ] Esperar o objeto no S3 e a linha em `notifications`; repetir ×5 e conferir 1 efeito. [ ] Exit 0 só se tudo passa. [ ] Quando o order/inventory Java estiverem verdes, conferir também no `happy-path.sh`.
- **Pronto quando:** exit 0 em stack limpa. **Valida:** o próprio script. **Mapeia:** S3, 2+ consumidores, R1.
