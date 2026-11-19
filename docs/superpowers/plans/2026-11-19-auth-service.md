# Auth-service (Java): plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (ou subagent-driven-development) para executar tarefa a tarefa, e superpowers:test-driven-development em cada uma. **Este plano só descreve trabalho: nenhum código, migration, teste ou script foi escrito ao produzi-lo.**

**Goal:** Completar o `auth-service` (hoje: register, login, JWKS) com **refresh rotativo, logout → `TokenRevoked` e compensação do cadastro (`UserCreationFailed`)**, de modo que `scripts/auth-check.sh` e `scripts/register-compensation.sh` (novos) e `scripts/gateway-check.sh` passem numa stack limpa até o **freeze de ter 24/11 às 12h**.

**Architecture:** Três fatias independentes sobre a `platform/` que o auth já tem ([RFC 0007](../../rfc/0007-design-do-auth-service-em-java.md)): (1) refresh + registro dos `jti` emitidos (única migration `V0006`); (2) logout e `TokenRevoked`; (3) consumidor de `UserCreationFailed` e compensação.

**Tech Stack:** Java 21, Spring Boot 4.0 (Jackson 3), JDBC puro, AWS SDK v2 (SNS/SQS), JUnit 5 + Testcontainers (Postgres 16, MiniStack). As mesmas versões do `pom.xml` atual (ADR-0018).

**Spec:** [RFC 0007](../../rfc/0007-design-do-auth-service-em-java.md); [RFC 0002 §5](../../rfc/0002-authentication-and-authorization.md); [RFC 0003 §5.4, §5.7](../../rfc/0003-failure-handling-strategy.md); [arquitetura §5](../../arquitetura.md); [padrões](../../padroes.md) §2.2, §4, §6, §7, §11.

## Global Constraints

- **Branch:** uma branch normal por tarefa, `feat/auth-service-<descrição>`. Nunca commitar na `main`, sem push sem pedir, sem git worktree. Commits Conventional com escopo (`feat(auth-service): rotate refresh tokens`).
- **Autonomia (ADR-0016):** nada de `../` em código, `Dockerfile` ou `pom.xml`.
- **Migrations:** V0001–V0005 **não se editam**; só `V0006__create_issued_access_tokens.sql` (RFC 0007 §5.4).
- **Contratos:** nenhuma mensagem nova; `TokenRevoked` e `UserRegistered` validam contra `contracts/examples` (`make -C contracts check` verde).
- **Segredos e PII:** nunca logar senha, hash, refresh token ou JWT.
- **Env, não hardcode:** variável nova entra no `.env.example` e no README.
- **Definition of Done de toda tarefa:** `cd services/auth-service && make lint typecheck test test-integration` verde; o script da tarefa com exit 0; README atualizado; falhas relatadas, não escondidas.
- **Ownership:** Felipe. Cuidado: ele também é dono do inventory (ADR-0021, plano do PR #13, T05–T08, T13). Conflito de agenda registrado em [Riscos](#riscos).

## Escopo e corte até o freeze

| Faixa | Tarefas | Se o prazo apertar |
|---|---|---|
| **Must** | A01 (docs e README), A02 (refresh), A03 (logout + `TokenRevoked`), A06 (script `auth-check.sh`) | Nunca cortar: sem elas a revogação por evento (R do enunciado: gateway com revogação) não tem produtor real. |
| **Should** | A04 (consumidor), A05 (compensação), A07 (`register-compensation.sh`) | Cortar A05/A07 por último e declarar no README e na apresentação que a compensação do cadastro é demonstrada por injeção de evento (`scripts/lib/aws.sh`), como já faz o `gateway-check.sh` para `TokenRevoked`. |
| **Could** | A08 (limpeza de `issued_access_tokens`) | Cortar primeiro. |

**Linha de corte (alinhada ao roadmap):** se A03 não estiver mergeada até **sáb 21/11**, A04/A05 só seguem se sobrar horas; dom 22/11 12h é o limite de todas as Must/Should.

### Cronograma

| Dia | Felipe | Checkpoint |
|---|---|---|
| qui 19/11 (resto) | A01 | README e `.env.example` honestos; link do RFC 0007 |
| sex 20/11 | A02 | refresh funcionando, `V0006` aplicada (**depende de G01** do gateway) |
| sáb 21/11 | A03, A06 | `auth-check.sh` verde: login → logout → gateway 401 |
| dom 22/11 até 12h | A04, A05 | consumidor ligado (reutiliza o runner de T02 do PR #13) |
| dom 22/11 tarde | A07, A08 | `register-compensation.sh` verde |
| seg 23/11 | correções, ensaio | — |
| ter 24/11 até 12h | freeze: só bloqueantes | ensaio cronometrado |

### Dependências

```
A01 ─ A02 ─ A03 ─ A06
        │      └──── A05 ─ A07
        └ (G01 gateway: refresh público)
A04 (runner de T02/PR #13) ─ A05
A08 (opcional, após A03)
```

## Mapa requisito/cenário → tarefa → script

| Item | Tarefas | Script |
|---|---|---|
| Gateway com revogação (enunciado, ADR-0014) | A02, A03 | `auth-check.sh`, `gateway-check.sh` |
| Saga curta com compensação (arquitetura §5) | A04, A05 | `register-compensation.sh` |
| R1 idempotência | A05 (`UserCreationFailed` ×5 → 1 efeito) | teste de integração + `register-compensation.sh` |
| R6 reconexão | A03 (logout com broker fora grava no outbox) | teste de integração |
| BOLA/segurança (RFC 0002 §5.8) | A02 (reuso → família revogada), A03 | testes unitários e `auth-check.sh` |

## Riscos

| # | Risco | Prob. | Mitigação |
|---|---|---|---|
| 1 | **Felipe acumula inventory (T05–T08/T13) e auth** nos mesmos 5 dias | Alta | Auth é Must só até A03/A06; A04/A05 dependem do runner de T02, que Matheus entrega sex 20/11; se faltar, injeção de evento na demo |
| 2 | Refresh bloqueado pelo gateway (rota não pública) | Alta | G01 do plano do gateway entra antes de A06 |
| 3 | Argon2id pesado com 2 réplicas e limite de memória | Média | Medir no ensaio; ajustar `ARGON2_MEMORY_KB` por env |
| 4 | Corrida compensação × login | Baixa | `SELECT … FOR UPDATE` em `credentials`; IT dedicado em A05 |
| 5 | Contradição documental sobre Redis/`revoked_tokens` (RFC 0007 Decisão 2) | Média | Registrada na [visão geral](2026-11-19-other-services-overview.md); implementação segue a opção Postgres até o grupo decidir |
| 6 | `UserCreationFailed` nunca disparado em fluxo real (RFC 0008) | Média | A07 usa injeção do evento quando o user-service não tiver gatilho |

## Review Focus

1. **Refresh:** o segundo uso do mesmo token revoga a família; concorrência testada com 2 threads.
2. **Logout:** idempotente, um `TokenRevoked` por `jti`, `expiresAt` = `exp` real.
3. **Compensação:** eventos `TokenRevoked` gerados antes do `DELETE`, tudo na mesma TX, duplicata ×5 = 1 efeito.
4. **Segredos fora do log.**

---

## Tarefas

### A01: README e `.env.example` honestos

- **Owner:** Felipe. **Depende de:** nada.
- **Files:** `services/auth-service/README.md`, `.env.example`.
- **Passos:** [ ] Corrigir "Estado" (F2 pronto: register, login, JWKS), remover menção a Redis e `revoked_tokens` (ou marcar como "decisão pendente, RFC 0007 D2"). [ ] Tabela de API com a coluna "Estado" real. [ ] Linkar o RFC 0007. [ ] Declarar `AUTH_ISSUED_TOKEN_CLEANUP_INTERVAL_S` (só quando A08 existir).
- **Pronto quando:** README coerente com [RFC 0007 §3](../../rfc/0007-design-do-auth-service-em-java.md); links resolvem.
- **Valida:** `scripts/check-autonomy.sh services/auth-service`.
- **Mapeia:** documentação.

### A02: Refresh rotativo e registro de `jti` emitidos

- **Owner:** Felipe. **Depende de:** A01; **G01** (gateway) para a verificação ponta a ponta.
- **Files:** `migrations/V0006__create_issued_access_tokens.sql`; `modules/session/RefreshController.java`, `RefreshService.java`; `LoginService`/`JwtIssuer` (gravar `jti`/`family_id`/`expires_at`); testes.
- **Passos (TDD):**
  - [ ] Testes primeiro: refresh feliz (novo par, mesma `family_id`, `used_at` preenchido), expirado, inexistente (401 genérico), **reuso** (família revogada), **concorrência** de dois refresh do mesmo token (um passa, o outro é reuso), `V0006` aplicada nas migrations de teste (`Migrations.java`).
  - [ ] Migration `V0006` (RFC 0007 §5.4) e escrita em login/refresh na mesma TX.
  - [ ] Implementar (RFC 0007 §5.2); `REFRESH_REUSE` só publica `TokenRevoked` para `jti` vivos da família.
- **Pronto quando:** testes unitários e IT verdes; `V0006` aplica no `auth-service-migrate` numa stack limpa.
- **Valida:** `make test-integration`; `scripts/verify-platform.sh`.
- **Mapeia:** RFC 0002 §5.3; base de revogação.

### A03: Logout e `TokenRevoked`

- **Owner:** Felipe. **Depende de:** A02.
- **Files:** `modules/session/LogoutController.java`, `LogoutService.java`, verificação do Bearer com `SigningKeys`.
- **Passos:**
  - [ ] Testes: logout válido → 204 e **um** evento no outbox com `reason=LOGOUT`, `expiresAt` = `exp`; logout repetido → 204 sem segundo evento; Bearer inválido → 401; refresh do corpo revogado; broker parado → 204 e evento fica no outbox (R6).
  - [ ] Implementar (RFC 0007 §5.3).
  - [ ] Validar o payload contra `contracts/examples/TokenRevoked.v1.json`.
- **Pronto quando:** testes verdes; o gateway devolve 401 para o token logo após o logout (A06).
- **Valida:** `scripts/auth-check.sh` (A06); `scripts/gateway-check.sh`.
- **Mapeia:** ADR-0014, RFC 0002 §5.1.

### A04: Consumidor de `UserCreationFailed` ligado

- **Owner:** Felipe. **Depende de:** runner do consumidor do PR #13 (T02, Matheus) ou cópia equivalente.
- **Files:** wiring do `SqsConsumer` (runner `SmartLifecycle`), `BrokerProbe` composto (`relay && consumer`), mapa de handlers.
- **Passos:** [ ] Copiar o runner de T02 (mesmo pacote e nome) em vez de reescrever. [ ] Handler no-op que registra `processed_messages`. [ ] IT: mensagem desconhecida vai à DLQ, nunca fica presa.
- **Pronto quando:** logs do compose mostram o consumidor ligado; `make test-integration` verde.
- **Valida:** `scripts/verify-platform.sh`; `scripts/dlq.sh` (lista a DLQ de `ticketpro-auth-service`).
- **Mapeia:** R1, R4, F4 (consumidor).

### A05: Compensação do cadastro

- **Owner:** Felipe. **Depende de:** A02 (tabela de `jti`), A04.
- **Files:** `modules/registration/UserCreationFailedHandler.java`.
- **Passos (RFC 0007 §5.5):**
  - [ ] Testes: credencial com 2 tokens vivos → 2 `TokenRevoked(ACCOUNT_REMOVED)`, credencial apagada, refresh revogados; sem tokens → só o `DELETE`; credencial inexistente → no-op; **mesma mensagem ×5 → 1 efeito**; login concorrente (IT com duas threads).
  - [ ] Implementar na TX: `SELECT … FOR UPDATE`, outbox antes do `DELETE`.
- **Pronto quando:** testes verdes; o login do usuário removido responde 401 e o token antigo é rejeitado pelo gateway.
- **Valida:** `scripts/register-compensation.sh` (A07).
- **Mapeia:** saga curta, R1.

### A06: `scripts/auth-check.sh`

- **Owner:** Felipe (Pedro revisa; o script usa `scripts/lib`). **Depende de:** A03, G01.
- **Passos:** [ ] Script no padrão de `gateway-check.sh`: register → login → `GET /me` 200 → refresh (200, novo par) → **refresh com o token antigo → 401 e família revogada** → logout → `GET /me` 401 (aguardar o evento, com timeout). [ ] Exit 0 só se todos passam; mensagem clara por etapa.
- **Pronto quando:** exit 0 em stack limpa.
- **Valida:** o próprio script. **Mapeia:** revogação ponta a ponta.

### A07: `scripts/register-compensation.sh`

- **Owner:** Felipe e Yasmin. **Depende de:** A05; gatilho do user-service (RFC 0008 Decisão 2) **ou** injeção do evento.
- **Passos:** [ ] Registrar um usuário, fazer login, publicar `UserCreationFailed` (no gatilho real ou por `scripts/lib/aws.sh`), esperar: login → 401, token antigo → 401 pelo gateway, `credentials` sem a linha (consulta com `psql` no `auth-db` somente para verificação do script). [ ] Repetir a publicação 5× e checar que nada muda.
- **Pronto quando:** exit 0 em stack limpa.
- **Valida:** o próprio script. **Mapeia:** saga curta, R1.

### A08 (Could): Limpeza de `issued_access_tokens`

- **Owner:** Felipe. **Depende de:** A03.
- **Passos:** [ ] Job `SKIP LOCKED` apagando `expires_at < now() - margem`; env `AUTH_ISSUED_TOKEN_CLEANUP_INTERVAL_S`. [ ] IT.
- **Pronto quando:** teste verde. **Valida:** `make test-integration`. **Mapeia:** retenção (ver visão geral).
