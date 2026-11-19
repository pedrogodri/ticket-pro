# RFC 0007 — Design do auth-service em Java

- **Status:** 📝 Draft (estado atual verificado em 19/11; o que falta está nas tarefas do [plano](../superpowers/plans/2026-11-19-auth-service.md))
- **Scope:** O `auth-service` (Java 21, Spring Boot): credenciais, login, **refresh rotativo, logout, revogação (`TokenRevoked`) e compensação do cadastro (`UserCreationFailed`)**, JWKS e a chave do Secrets Manager. Documenta o que já existe e separa o que falta. Usa a camada `platform/` do próprio serviço (a mesma que o RFC 0004, do PR #13, copia para order e inventory) e não a redefine. **Sem mudança de contrato.** Uma migration nova é necessária (Decisão 3). Não cobre a validação no gateway (RFC 0010).
- **Related:** [RFC 0002](0002-authentication-and-authorization.md), [RFC 0003 §5.4, §5.7](0003-failure-handling-strategy.md), [ADR-0002](../adr/0002-gateway-validates-jwt-locally-via-jwks.md), [ADR-0014](../adr/0014-token-revocation-via-event.md), [arquitetura §5](../arquitetura.md), [`services/auth-service/`](../../services/auth-service/README.md), [`contracts/events/TokenRevoked`](../../contracts/events/TokenRevoked/v1.schema.json), [`contracts/events/UserCreationFailed`](../../contracts/events/UserCreationFailed/v1.schema.json), [RFC 0008](0008-design-do-user-service-em-csharp.md), [RFC 0010](0010-design-do-gateway-em-go.md)

---

## 1. Summary

O auth já cadastra, faz login (JWT RS256, refresh guardado como hash SHA-256) e publica o JWKS com a chave lida do Secrets Manager. Faltam três coisas para fechar o RFC 0002: `POST /auth/refresh` (rotação + detecção de reuso), `POST /auth/logout` (revoga e publica `TokenRevoked`) e o consumidor de `UserCreationFailed` (compensação do cadastro). As duas últimas exigem saber o `jti`/`exp` dos access tokens emitidos, e hoje nada os guarda: é a única mudança de schema prevista.

## 2. Motivation

- A revogação (ADR-0014) e a compensação do cadastro (arquitetura §5) são requisitos do enunciado (saga com compensação, gateway com revogação). Sem `logout`, o gateway nunca recebe `TokenRevoked` do dono do evento: o `scripts/gateway-check.sh` só passa porque **publica o evento à mão** no tópico.
- Nenhum script cobre refresh, logout ou compensação do cadastro (`grep` em `scripts/`: só `gateway-check.sh` toca em revogação). Sem verificação automática, o ensaio de 24/11 não prova esses fluxos.

## 3. Current state

Verificado em 19/11 em `services/auth-service`:

| Item | Estado | Evidência |
|---|---|---|
| `POST /auth/register` → `201 {userId}`, credencial `ACTIVE` + outbox `UserRegistered` na mesma TX; e-mail duplicado → 409 | ✅ | `RegistrationService`, `AuthEndpointsTest`, `AuthDomainIT` |
| `POST /auth/login`: erro genérico, hash fictício quando o e-mail não existe (custo igual), refresh opaco 256 bits guardado como SHA-256, família nova por login | ✅ | `LoginService` |
| JWKS `GET /.well-known/jwks.json` (kid atual + anterior), chave `{current, previous}` lida do Secrets Manager e recarregada a cada `JWT_KEY_REFRESH_S` | ✅ | `modules/keys/*`, `KeysTest` |
| `platform/` completa: envelope, `SqsConsumer`, outbox + relay, `processed_messages`, retry, health `db`/`broker`, log JSON | ✅ | `PlatformIT` (p07–p16) |
| `POST /auth/refresh`, `POST /auth/logout` | ❌ | não há controller |
| Consumidor de `UserCreationFailed` | ❌ | `SqsConsumer` existe, mas **nenhum bean o instancia nem registra handler** (`AppConfigConfiguration` só liga o relay) |
| Registro dos `jti` emitidos / blacklist | ❌ | `JwtIssuer` devolve `IssuedToken` e nada persiste `jti` |
| Migrations V0001–V0005 | roles, `outbox`, `processed_messages`, `credentials` (`status CHECK IN ('ACTIVE')`), `refresh_tokens` (`family_id`, `used_at`, `revoked_at`; RLS) | `services/auth-service/migrations/` |
| Redis do auth | ❌ não existe no `compose.yaml`; só `auth-db` | ver Decisão 2 |
| README | declara Redis, tabela `revoked_tokens` e rotas "F2" | **divergente do código**, ver Decisão 2 |

Fato que condiciona o logout: o gateway **não** injeta `jti` (só `X-User-Id`/`X-User-Email`), mas também não remove `Authorization`; o proxy reverso repassa o header ao auth. O auth tem as chaves e pode validar o próprio token.

## 4. Goals / Non-goals

**Goals**

- Refresh com rotação e **reuso → família revogada** (RFC 0002 §5.3).
- Logout idempotente que revoga o refresh e o `jti` do access e publica `TokenRevoked(LOGOUT)` pelo outbox.
- Compensação de `UserCreationFailed`: apaga a credencial, revoga as famílias e publica `TokenRevoked(ACCOUNT_REMOVED)` para cada access ainda válido, tudo numa TX.
- Cada fluxo com um script que o prova numa stack limpa.

**Non-goals**

- Rotação automática de chave, MFA, recuperação de senha (RFC 0002 §8).
- Introspecção síncrona pelo gateway (anularia o ADR-0002).
- Redis no auth (Decisão 2).

## 5. Design

### 5.1 Endpoints (estado alvo)

| Rota | Público no gateway | Role de banco | Resposta |
|---|---|---|---|
| `POST /auth/register` | sim | worker (não há usuário para o RLS) | 201 / 409 / 400 |
| `POST /auth/login` | sim | worker | 200 `{accessToken, refreshToken, expiresIn}` / 401 `INVALID_CREDENTIALS` |
| `POST /auth/refresh` | sim | worker | 200 novo par / 401 |
| `POST /auth/logout` | **não** (exige Bearer) | worker | 204 (idempotente) |
| `GET /.well-known/jwks.json` | não roteado | — | chaves públicas |

**Pré-requisito no gateway:** hoje `cmd/gateway/main.go:85` só marca `POST /auth/register` e `POST /auth/login` como públicas, apesar de o RFC 0002 §5.5 listar também `refresh`. Com um access token expirado (o motivo do refresh), a rota daria 401 no gateway. A mudança é de uma linha e está na tarefa G1 do [plano do gateway](../superpowers/plans/2026-11-19-gateway.md).

Todas as rotas usam o formato de erro de `padroes §4.1`. Corpos com campo desconhecido → 400 (já configurado no Jackson).

### 5.2 Refresh com rotação (RFC 0002 §5.3)

Uma TX no worker:

1. `SELECT … FROM refresh_tokens WHERE token_hash = sha256(:t) FOR UPDATE`. Não existe ou `expires_at` vencido → 401 genérico.
2. `used_at IS NULL AND revoked_at IS NULL` → `UPDATE … SET used_at = now()`, insere o filho da **mesma `family_id`**, emite novo access e responde 200.
3. `used_at IS NOT NULL` (**reuso**) ou `revoked_at IS NOT NULL` → `UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = :f AND revoked_at IS NULL`, grava `TokenRevoked(REFRESH_REUSE)` no outbox para os `jti` ainda vivos da família (tabela do §5.4) e responde 401.

O `FOR UPDATE` serializa dois refresh simultâneos do mesmo token: o segundo vê `used_at` e cai no reuso. Esse é o comportamento correto de segurança, e o cliente legítimo com retry de rede paga com um novo login (aceito, documentar no README).

### 5.3 Logout

`Authorization: Bearer <jwt>` é validado **pelo próprio auth** com a chave de verificação já carregada (`SigningKeys`): assinatura, `iss`, `aud`, `exp`. O gateway já validou, mas o auth precisa do `jti` e do `exp`, que não viajam em header. Em uma TX: revoga a família do refresh enviado no corpo (`{refreshToken}`, opcional), grava `revoked_access_tokens(jti, expires_at)` e o outbox `TokenRevoked {jti, userId, expiresAt, reason: LOGOUT}`. Repetir o logout com o mesmo token → 204 e **sem segundo evento** (`ON CONFLICT (jti) DO NOTHING`; só publica quando inseriu).

### 5.4 Registro de access tokens emitidos (única migration)

Para a compensação e o `REFRESH_REUSE` o auth precisa listar os `jti` ainda válidos de um usuário (RFC 0002 §5.7 já prevê `issued_access_tokens`). Proposta, `V0006__create_issued_access_tokens.sql`:

```sql
CREATE TABLE issued_access_tokens (
  jti         uuid        PRIMARY KEY,
  user_id     uuid        NOT NULL,   -- sem FK: a credencial pode ser apagada na compensação
  family_id   uuid        NOT NULL,
  expires_at  timestamptz NOT NULL,
  revoked_at  timestamptz
);
CREATE INDEX issued_access_tokens_user_idx   ON issued_access_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX issued_access_tokens_family_idx ON issued_access_tokens (family_id);
CREATE INDEX issued_access_tokens_expiry_idx ON issued_access_tokens (expires_at);
```

Escrita no `login` e no `refresh`, na mesma TX do refresh. Limpeza por `expires_at < now()` num job periódico (`SKIP LOCKED`, padrão da arquitetura §9) ou oportunista no login. Sem RLS por usuário: a tabela é usada só por worker (documentar: é a exceção justificada ao `padroes §6.1`, porque quem escreve é o fluxo de login, ainda sem `app.user_id`).

### 5.5 Compensação do cadastro

`UserCreationFailedHandler` no `SqsConsumer` (fila `ticketpro-auth-service`). Pelo RFC 0003 §5.4: valida → abre TX → `processed_messages` → efeito → commit → ack. Efeito, numa TX:

```
SELECT jti, expires_at FROM issued_access_tokens WHERE user_id = :u AND revoked_at IS NULL AND expires_at > now()
UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :u
outbox: TokenRevoked(jti, userId, expiresAt, ACCOUNT_REMOVED) por jti vivo
DELETE FROM credentials WHERE user_id = :u        -- ON DELETE CASCADE apaga refresh_tokens
```

Credencial já inexistente (evento reentregue ou fora de ordem) → no-op, **sem erro**. `processed_messages` cobre a duplicata exata. A ordem importa: gerar os `TokenRevoked` antes do `DELETE`.

O consumidor precisa ser **ligado**: hoje o `SqsConsumer` não é instanciado. A tarefa copia o wiring de runner que o plano do PR #13 cria em T02 para order/inventory (mesmo `SmartLifecycle`), para não haver duas soluções.

### 5.6 Eventos

| Publica (`ticketpro-auth`) | Quando | Payload |
|---|---|---|
| `UserRegistered` v1 | register ✅ | `{userId, email, name}`; `name` 1–120 caracteres |
| `TokenRevoked` v1 | logout, reuso de refresh, compensação | `{jti, userId, expiresAt, reason ∈ LOGOUT, ACCOUNT_REMOVED, REFRESH_REUSE}` |

| Consome (`ticketpro-auth-service`) | Efeito |
|---|---|
| `UserCreationFailed` v1 `{userId, reason ∈ INVALID_PROFILE_DATA, DUPLICATE_PROFILE, INTERNAL_ERROR}` | §5.5 |

### 5.7 Invariantes

1. Nunca existe credencial `ACTIVE` sem `UserRegistered` publicado (mesma TX, já garantido).
2. Um refresh token é usado no máximo uma vez; o segundo uso revoga a família.
3. Todo `TokenRevoked` sai do outbox na TX que muda o estado (nunca direto no SNS).
4. `expiresAt` do evento é o `exp` real do access, para o TTL do gateway ser exato.
5. Compensação e logout são idempotentes (evento reentregue, clique duplo).

### 5.8 Falhas

| Falha | Comportamento |
|---|---|
| Secrets Manager sem a chave no boot | o serviço falha e o compose reinicia (`restart: on-failure`); esperado até o `infra-init` terminar |
| Secrets Manager fora depois do boot | continua com a chave em memória; o log avisa a cada `JWT_KEY_REFRESH_S` |
| Broker fora | outbox acumula; relay com backoff (R6); `/health/ready` degradado, 200 |
| Evento venenoso (`UserCreationFailed` inválido) | 3 recebimentos → DLQ (RFC 0003 §5.4) |
| Banco fora | `/health/ready` 503; API devolve 503 `problem+json` |

### 5.9 Observabilidade e configuração

Logs JSON (`correlationId`, `messageId`, `type`, `traceId`); **nunca** logar senha, hash, refresh token ou JWT. Configuração já em `.env.example` (`JWT_*`, `ARGON2_*`, `OUTBOX_*`). Novas, todas com padrão: `AUTH_ISSUED_TOKEN_CLEANUP_INTERVAL_S` (300), `SQS_WAIT_TIME_S`, `CONSUMER_CONCURRENCY` (já no padrão de consumidores). OpenTelemetry em Java é a tarefa T14 do plano do PR #13; o auth entra nela, fora deste RFC.

## 6. Tests

- Unitário: reuso de refresh, expirado, logout sem corpo, `reason` do evento.
- IT (Testcontainers Postgres + MiniStack, padrão `AuthDomainIT`): refresh concorrente (duas threads, o segundo é reuso), logout duas vezes → 1 evento, compensação com e sem tokens vivos, `UserCreationFailed` ×5 → 1 efeito (R1), credencial inexistente → no-op.
- Contrato: `TokenRevoked` e `UserRegistered` validam contra `contracts/examples`.
- Ponta a ponta: `scripts/auth-check.sh` e `scripts/register-compensation.sh` (novos, no plano).

## 7. Docs

Corrigir o README do auth (estado F2/F3, Redis e `revoked_tokens` que não existem) e registrar o refresh "uma vez só" (§5.2). Atualizar o RFC 0002 só por **nova linha de decisão**, não por reescrita da prosa.

## 8. Out of scope

- Redis no auth e job de rotação de chave.
- Limpeza de `processed_messages` e do outbox (retenção prometida pelos ADR-0005/0006, não implementada em nenhum serviço; ver visão geral).
- Troca de senha, bloqueio de conta, e-mail único case-insensitive além do `CHECK email = lower(email)` já existente.

## 9. Risks

- **Escopo para quem tem 5 dias:** logout e compensação dependem da migration e do consumidor ligado; o refresh é independente. Ordem no plano: refresh e logout primeiro (provam `TokenRevoked` real), compensação em seguida.
- **Argon2id no `register`/`login` com 2 réplicas e limite de memória do container:** 19 MB por hash em paralelo; medir no ensaio (o F5 usa N usuários).
- **Corrida compensação × login:** um login concluído entre o `SELECT` e o `DELETE` emite um access que não será revogado. A TX com `SELECT … FOR UPDATE` na credencial fecha a janela (o login lê `credentials` e faz `INSERT` em `issued_access_tokens`; o delete espera). Verificar com IT.
- **Fail-open do gateway** continua o limite de 15 min do ADR-0014.

## 10. Unresolved questions

- **Gatilho da compensação:** o auth já rejeita `name` vazio ou só de espaços (`Requests.text` faz `trim` e exige 1–100 caracteres, mais estrito que o contrato, que aceita até 120), e o e-mail é único. Logo **nenhum cadastro real produz hoje um erro de perfil**: sem uma regra determinística no user-service (RFC 0008, Decisão 2) a compensação só é exercitável injetando `UserCreationFailed` à mão. Quem decide: Yasmin e Felipe.
- **Onde guardar a blacklist** (Decisão 2): precisa do aceite do grupo, pois contradiz texto de ADR aceito.

## 11. Decisions

| # | Grade | Decision |
| --- | --- | --- |
| 1 | `LOCKED` | Toda revogação sai pelo outbox como `TokenRevoked`; o gateway é o único consumidor e nunca consulta o auth por request (ADR-0002/0014). |
| 2 | `OPEN` | O estado de revogação do auth fica **no Postgres do auth**, sem Redis. ADR-0014 e a arquitetura §2/§8 citam "Redis do auth" e `revoked_tokens`; RFC 0002 §5.7 cita `issued_access_tokens`; o código não tem nenhum dos dois. Proposta: Postgres, porque o `compose.yaml` e os `.env` já não têm Redis e o auth nunca lê a blacklist em caminho quente. Registrar em divergência D-A1; mudar o ADR exige ADR novo. |
| 3 | `ASSUMED` | Uma migration `V0006` cria `issued_access_tokens` (§5.4). Sem ela a compensação não sabe quais `jti` revogar. |
| 4 | `ASSUMED` | O logout valida o Bearer no próprio auth (chave local) em vez de o gateway injetar `X-Token-Id`. Evita mudar o gateway (RFC 0002 §5.5 lista os headers injetados). |
| 5 | `ASSUMED` | Reuso de refresh responde 401 genérico e revoga a família inteira; sem distinção de erro para o cliente. |
| 6 | `ASSUMED` | O consumidor de `UserCreationFailed` reutiliza o runner do plano do PR #13 (T02) em vez de uma segunda implementação. |
| 7 | `OPEN` | Limpeza de `issued_access_tokens`: job periódico ou oportunista no login. Decidir na implementação; o volume da demo não exige nenhuma. |

## 12. Phasing

- **P1:** `refresh` + `V0006` + registro de `jti` em login/refresh (sem consumidor).
- **P2:** `logout` + `TokenRevoked` + `scripts/auth-check.sh` (inclui o gateway devolvendo 401 depois).
- **P3:** consumidor de `UserCreationFailed` + compensação + `scripts/register-compensation.sh`.
