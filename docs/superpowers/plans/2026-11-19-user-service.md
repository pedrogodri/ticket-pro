# User-service (C#): plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (ou subagent-driven-development) para executar tarefa a tarefa, e superpowers:test-driven-development em cada uma. **Este plano só descreve trabalho: nenhum código, migration, teste ou script foi escrito ao produzi-lo.**

**Goal:** Fechar o `user-service` (perfil, `GET /me`, `GET /me/tickets` já prontos) com a **publicação de `UserCreationFailed`**, o **teste de BOLA por HTTP** e a documentação, até o **freeze de ter 24/11 às 12h**.

**Architecture:** Sem camada nova: o `UserRegisteredHandler` ganha saídas de falha de negócio que escrevem no outbox na TX do consumidor ([RFC 0008](../../rfc/0008-design-do-user-service-em-csharp.md)). A leitura continua por `users_app` + RLS.

**Tech Stack:** C# / .NET 10, Npgsql, AWS SDK, xUnit + Testcontainers; `Platform/` vendorizada (não editar; mudança em `shared/dotnet` + `scripts/sync-shared.sh`).

**Spec:** [RFC 0008](../../rfc/0008-design-do-user-service-em-csharp.md); [RFC 0002 §5.6–5.7](../../rfc/0002-authentication-and-authorization.md); [RFC 0003 §5.4](../../rfc/0003-failure-handling-strategy.md); [arquitetura §5, §8](../../arquitetura.md); [padrões](../../padroes.md) §2.3, §6.1, §11.

## Global Constraints

- **Branch:** `feat/user-service-<descrição>`; nunca na `main`; sem push sem pedir; sem worktree. Commits `feat(user-service): ...`.
- **Nunca editar** `src/UserService/Platform/` nem `contracts/` (cópias); mudança entra em `shared/` ou `contracts/` da raiz.
- **Migrations:** V0001–V0004 não se editam; `V0005` só se a Decisão 3 do RFC 0008 for adotada.
- **Contratos:** `UserCreationFailed` valida contra `contracts/examples/UserCreationFailed.v1.json`.
- **Definition of Done:** `cd services/user-service && make lint typecheck test` verde (e os testes `Category=Integration`); script da tarefa com exit 0; README atualizado.
- **Ownership:** Yasmin. A decisão do gatilho (U02) é com Felipe (auth) e o grupo.

## Escopo e corte até o freeze

| Faixa | Tarefas | Se o prazo apertar |
|---|---|---|
| **Must** | U01 (BOLA + README), U02 (decidir gatilho) | O teste de BOLA é item explícito do roadmap F3. |
| **Should** | U03 (handler com `UserCreationFailed`), U04 (script com A07) | Cortar U03/U04 por último; sem eles a compensação é demonstrada por injeção de evento (ver plano do auth, A07). |
| **Could** | U05 (índice único de e-mail) | Cortar primeiro. |

**Linha de corte:** U01 até sex 20/11; U03 até sáb 21/11; dom 22/11 12h é o limite.

### Cronograma

| Dia | Yasmin | Checkpoint |
|---|---|---|
| qui 19/11 | U01 (README), U02 (reunião curta) | gatilho decidido e anotado no RFC 0008 (nova linha de decisão) |
| sex 20/11 | U01 (BOLA) | teste de BOLA verde |
| sáb 21/11 | U03 | `UserCreationFailed` no outbox em teste |
| dom 22/11 até 12h | U04 (com Felipe) | `register-compensation.sh` verde |
| seg–ter | correções, ensaio | freeze ter 12h |

### Dependências

```
U01 ─ U02 ─ U03 ─ U04 (com A05/A07 do auth)
              └ U05 (opcional)
```

## Mapa requisito/cenário → tarefa → script

| Item | Tarefas | Script |
|---|---|---|
| Bônus CQRS (read model) | já entregue; U01 mantém o teste | `happy-path.sh` (passo `/me/tickets`) |
| Evento com 2+ consumidores | `TicketsIssued` projetado (entregue) | `happy-path.sh` |
| BOLA (RFC 0002 §5.6) | U01 | teste xUnit; `happy-path.sh` não cobre B×A |
| Saga curta com compensação | U03, U04 | `register-compensation.sh` |
| R1 | U03 (`UserRegistered` ×5) | teste de integração |

## Riscos

| # | Risco | Prob. | Mitigação |
|---|---|---|---|
| 1 | Sem gatilho natural para `UserCreationFailed` (o auth já valida tudo) | Alta | U02 antes de U03; fallback por injeção de evento |
| 2 | `GET /me` 404 logo após o cadastro (consistência eventual) | Média | Scripts repetem a leitura com timeout |
| 3 | Mudança em `Platform/` exigida pelo handler | Baixa | O `OutboxWriter` já serve; se faltar, alterar `shared/dotnet` e sincronizar |
| 4 | Dono com pouca folga (user-service é leve, mas depende de decisão alheia) | Baixa | Tarefas U01 independentes |

## Review Focus

1. Falha de negócio = outbox + ack; erro transitório **não** compensa.
2. `userId` inválido não vira `UserCreationFailed` (vai à DLQ).
3. Nenhuma rota com `userId` na URL; RLS `FORCE` mantido.

---

## Tarefas

### U01: Teste de BOLA por HTTP e README

- **Owner:** Yasmin. **Depende de:** nada.
- **Files:** `tests/UserService.Tests/Integration/MeEndpointsTests.cs`, `README.md`.
- **Passos:** [ ] Teste: usuário A com perfil e ingressos; B (sem perfil) → `GET /me` 404 e `GET /me/tickets` vazio; B com perfil não vê os ingressos de A. [ ] README: read model sai de `TicketsIssued`, `OrderConfirmed` é inócuo, estado atual, link do RFC 0008.
- **Pronto quando:** testes verdes; README coerente com [RFC 0008 §3](../../rfc/0008-design-do-user-service-em-csharp.md).
- **Valida:** `make test`; `scripts/check-autonomy.sh services/user-service`.
- **Mapeia:** BOLA, CQRS.

### U02: Decidir o gatilho de `UserCreationFailed`

- **Owner:** Yasmin e Felipe (decisão do grupo). **Depende de:** nada.
- **Passos:** [ ] Escolher entre (a) regra de nome/e-mail reservado, (b) nenhum gatilho com injeção de evento (RFC 0008 Decisão 2). [ ] Registrar a escolha como **nova linha** da tabela de decisões do RFC 0008 (não reescrever a prosa). [ ] Se (a), alinhar com o auth se algo precisa mudar lá.
- **Pronto quando:** linha de decisão registrada e comunicada no grupo.
- **Valida:** n/a (decisão). **Mapeia:** saga curta.

### U03: Handler com falha de negócio

- **Owner:** Yasmin. **Depende de:** U02.
- **Files:** `Modules/Profiles/Profiles.cs`, `Program.cs` (passar `config.UsersTopicArn` ao handler), testes.
- **Passos (RFC 0008 §5.2):**
  - [ ] Testes primeiro: gatilho → 1 linha no outbox `UserCreationFailed` e **nenhum** perfil; mesma mensagem ×5 → 1 outbox; `userId` inválido → exceção (DLQ); exceção transitória não gera evento.
  - [ ] Implementar com `OutboxWriter.EnqueueAsync` na TX do consumidor, `correlationId` do envelope.
  - [ ] Validar o envelope contra o exemplo do contrato.
- **Pronto quando:** testes verdes; relay publica no `ticketpro-users`.
- **Valida:** `make test`; `scripts/verify-platform.sh`.
- **Mapeia:** saga curta, R1.

### U04: Verificação ponta a ponta da compensação

- **Owner:** Yasmin com Felipe. **Depende de:** U03, A05 (auth).
- **Passos:** usar `scripts/register-compensation.sh` (criado em A07): cadastro com o gatilho → `UserCreationFailed` → login 401 e token antigo 401.
- **Pronto quando:** exit 0 em stack limpa. **Valida:** `scripts/register-compensation.sh`. **Mapeia:** saga curta.

### U05 (Could): Índice único por e-mail

- **Owner:** Yasmin. **Depende de:** U03.
- **Passos:** [ ] `V0005__profiles_unique_email.sql` (`UNIQUE (lower(email))`); handler mapeia a violação para `DUPLICATE_PROFILE`. [ ] Teste. 
- **Pronto quando:** teste verde. **Valida:** `make test`. **Mapeia:** invariante do perfil.
