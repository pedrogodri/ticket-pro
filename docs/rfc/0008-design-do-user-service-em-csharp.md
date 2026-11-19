# RFC 0008 — Design do user-service em C#

- **Status:** 📝 Draft (estado atual verificado em 19/11; o que falta está no [plano](../superpowers/plans/2026-11-19-user-service.md))
- **Scope:** O `user-service` (C#, .NET 10): perfil projetado de `UserRegistered`, `GET /me`, read model CQRS `GET /me/tickets` (projetado de `TicketsIssued`) e a **publicação de `UserCreationFailed`**, que é o que falta. Cobre API, eventos, modelo de dados, invariantes, falhas e a regra de BOLA por RLS. **Sem mudança de contrato; sem migration obrigatória** (uma opcional na Decisão 3). Não cobre o auth (RFC 0007) nem a validação do JWT (RFC 0010).
- **Related:** [RFC 0002 §5.6, §5.7](0002-authentication-and-authorization.md), [RFC 0003 §5.4, §5.7](0003-failure-handling-strategy.md), [ADR-0006](../adr/0006-idempotent-consumers-processed-messages.md), [ADR-0020](../adr/0020-database-roles-and-data-networks.md), [arquitetura §5, §6, §8](../arquitetura.md), [`services/user-service/`](../../services/user-service/README.md), [`contracts/events/UserCreationFailed`](../../contracts/events/UserCreationFailed/v1.schema.json), [RFC 0007](0007-design-do-auth-service-em-java.md)

---

## 1. Summary

O user-service já projeta o perfil e o read model "meus ingressos" e os serve com RLS por `app.user_id`. Falta o lado de falha do cadastro: se o perfil não puder ser criado, o serviço deve **publicar `UserCreationFailed` e dar ack** (compensação no auth), em vez de lançar exceção e mandar a mensagem à DLQ. Isso exige uma regra de negócio determinística que hoje não existe.

## 2. Motivation

O cadastro é a saga curta com compensação do projeto (arquitetura §5), e o roadmap F3 lista "auth/user: `UserCreationFailed` → compensação". Hoje o `UserRegisteredHandler` faz `GetProperty(...)` e `INSERT … ON CONFLICT DO NOTHING`: payload inválido lança exceção (venenosa → DLQ, o cadastro **nunca** é compensado) e conflito é silenciosamente ignorado. O evento `UserCreationFailed` existe no contrato e na fila do auth, mas **nada o produz** em todo o repositório.

## 3. Current state

Verificado em 19/11 em `services/user-service`:

| Item | Estado | Evidência |
|---|---|---|
| `UserRegistered` → `profiles(user_id PK, email, name)` com `ON CONFLICT (user_id) DO NOTHING` | ✅ | `Modules/Profiles/Profiles.cs`, `ReadModelTests.UserRegisteredCreatesTheProfileOnce` |
| `TicketsIssued` → uma linha em `my_tickets` por ingresso (`ticket_id PK`, `ticket_code UNIQUE`, `ON CONFLICT DO NOTHING`) | ✅ | `Modules/MyTickets/MyTickets.cs` |
| `OrderConfirmed`: aceito sem efeito (o read model sai de `TicketsIssued`) | ✅ (inócuo) | `OrderConfirmedHandler` |
| `GET /me` (404 enquanto o perfil não foi projetado), `GET /me/tickets` (`limit` 1–50, cursor keyset `(issued_at, ticket_id)`) | ✅ | `App/MeEndpoints.cs`, `MeEndpointsTests` |
| RLS `ENABLE` + `FORCE` nas duas tabelas, leitura pelo role `users_app` com `SET LOCAL app.user_id` | ✅ | `V0004`, `App/UserScope.cs` |
| Relay do outbox e consumidor ligados; `processed_messages` | ✅ | `Program.cs` |
| Publicar `UserCreationFailed` | ❌ | nenhuma referência fora de `contracts/` |
| Regra de validação do perfil (o que é um perfil inválido/duplicado) | ❌ | não definida |
| Teste de BOLA por HTTP (usuário B não vê dados de A) | parcial | existe a prova em nível de query (`...OnlyForTheirOwner`); falta o teste dos endpoints com dois usuários |
| README | descreve `OrderConfirmed` como fonte do read model e lista a Task 10 do plano F1 como pendente | desatualizado |

Dado relevante: `UserRegistered.name` é aceito pelo schema com 1–120 caracteres, e o auth já exige 1–100 após `trim`. `profiles.email` **não** é único.

## 4. Goals / Non-goals

**Goals**

- Perfil inválido ou duplicado vira `UserCreationFailed(reason)` pelo outbox, **na mesma TX do `processed_messages`**, com ack normal.
- A regra de falha é determinística e demonstrável por script.
- BOLA provado por HTTP com dois usuários.

**Non-goals**

- Edição de perfil (`PATCH /me`): o enunciado não pede.
- Consultar o auth ou o inventory de forma síncrona (ADR-0004).
- Reprocessar ingressos históricos (rebuild do read model): fora do prazo.

## 5. Design

### 5.1 API (via gateway)

| Rota | Autenticação | Resposta |
|---|---|---|
| `GET /me` | `X-User-Id` obrigatório (sem ele → 401) | 200 `{userId, email, name}`; 404 `NOT_FOUND` enquanto não projetado |
| `GET /me/tickets?limit&cursor` | idem | 200 `{items, nextCursor}`; 400 para `limit`/`cursor` inválido |
| `GET /health/live`, `/health/ready` | — | `db` e `broker` |

Não existe rota com `userId` na URL (RFC 0002 §5.6): BOLA é estrutural. Mesmo que uma consulta esquecesse o `WHERE`, o RLS (`FORCE`) devolve zero linhas para outro usuário.

### 5.2 Projeção do perfil com falha explícita

`UserRegisteredHandler` passa a ter três saídas, todas **sem exceção** e dentro da TX que o `SqsConsumer` já abre:

```
validar payload (userId uuid, email, name após trim 1–100)
  └ inválido                      → outbox UserCreationFailed(INVALID_PROFILE_DATA)
INSERT INTO profiles … ON CONFLICT DO NOTHING
  ├ 1 linha                       → ok
  └ 0 linhas, MESMO user_id       → ok (duplicata de entrega; processed_messages já cobre)
  └ conflito por e-mail de OUTRO user_id (se a Decisão 3 criar o índice) → outbox UserCreationFailed(DUPLICATE_PROFILE)
exceção inesperada                → deixa propagar (transitória: retry do SQS → DLQ), nunca INTERNAL_ERROR
```

`UserCreationFailed` é produzido via `OutboxWriter.EnqueueAsync(connection, transaction, config.UsersTopicArn, envelope, ct)`; o handler recebe o `topicArn` no construtor (hoje o construtor é sem argumentos). `correlationId` é o do envelope recebido. Payload `{userId, reason}` com `reason ∈ INVALID_PROFILE_DATA, DUPLICATE_PROFILE, INTERNAL_ERROR`. `INTERNAL_ERROR` só existe para uso explícito (Decisão 2); erros transitórios não compensam, porque derrubariam um usuário válido por uma falha de banco.

Um `userId` que não é UUID não pode ser reportado (o campo do evento exige uuid): nesse caso a mensagem é venenosa de verdade e vai à DLQ.

### 5.3 Read model "meus ingressos"

Inalterado: idempotente por `ticket_id`; `TicketsIssued` de um usuário sem perfil (compensado ou ainda não projetado) **é gravado mesmo assim**, porque o evento é a fonte da verdade e `GET /me/tickets` é por `user_id`. Consistência eventual entre as duas projeções é aceita (arquitetura §5).

### 5.4 Dados

`profiles(user_id PK, email, name, created_at)`; `my_tickets` (índice `(user_id, issued_at DESC, ticket_id DESC)` serve a paginação); `outbox`; `processed_messages`. Roles: `users_app` (API, RLS) e `users_worker` (consumidor, relay, `BYPASSRLS`). Nenhuma tabela de outro serviço é lida.

### 5.5 Eventos

| Publica (`ticketpro-users`) | Consome (`ticketpro-user-service`) |
|---|---|
| `UserCreationFailed` v1 | `UserRegistered` v1, `TicketsIssued` v1, `OrderConfirmed` v1 (sem efeito) |

### 5.6 Invariantes

1. Um `user_id` tem no máximo um perfil; um `ticket_id`/`ticket_code` aparece no máximo uma vez.
2. Para cada `UserRegistered` entregue existe exatamente um destes: perfil, ou um `UserCreationFailed` no outbox (nunca os dois, nunca nenhum, exceto DLQ de mensagem venenosa).
3. Rota HTTP usa só `users_app` (RLS); consumidor/relay usam só `users_worker`.
4. Ack só depois do commit (RFC 0003 §5.4).

### 5.7 Falhas

| Falha | Comportamento |
|---|---|
| Duplicata ×5 de `UserRegistered` | 1 perfil, 0 `UserCreationFailed` extras (`processed_messages`) |
| Banco fora | `/health/ready` 503; consumidor repete; mensagem volta após o visibility timeout |
| Broker fora | outbox acumula, relay com backoff (R6) |
| `GET /me` logo após o cadastro | 404 por alguns segundos (aceito, RFC 0002 §5.7); o script de verificação deve **repetir** a leitura |
| `UserCreationFailed` publicado e o auth fora | fila do auth retém; compensação ocorre quando voltar |

### 5.8 Observabilidade e configuração

Logs JSON com `correlationId`, `messageId`, `type`; nunca logar e-mail completo em nível `info` (PII; o log do consumidor já é estruturado, manter `userId`). Spans de consumer/producer já vêm do `Platform`. Configuração por env (já no `.env.example`): `USERS_TOPIC_ARN`, `QUEUE_URL`, `CONSUMER_CONCURRENCY`, `SQS_WAIT_TIME_S`, `OUTBOX_*`. **Nova:** `PROFILE_NAME_MAX_LENGTH` (padrão 100) só se a Decisão 2 escolher o nome como gatilho.

## 6. Tests

xUnit com Testcontainers, no padrão de `ReadModelTests`: nome inválido → 1 linha no outbox `UserCreationFailed` e **nenhum** perfil; mesma mensagem ×5 → 1 outbox; conflito de e-mail (Decisão 3); payload sem `userId` → exceção (DLQ); teste de BOLA em `MeEndpointsTests` com usuários A e B (A lê o próprio, B nunca vê os ingressos de A, `GET /me` de B → 404 enquanto B não tem perfil). Contrato: o envelope publicado valida contra `contracts/examples/UserCreationFailed.v1.json`.

## 7. Docs

Corrigir o README (read model sai de `TicketsIssued`; `OrderConfirmed` é inócuo; estado atual). Documentar a regra de falha escolhida na Decisão 2 no README e no runbook.

## 8. Out of scope

Rebuild do read model, `PATCH /me`, paginação reversa, limpeza de `processed_messages` (retenção dos ADR-0005/0006 não implementada em nenhum serviço).

## 9. Risks

- **Sem gatilho natural, a compensação do cadastro fica inatingível na demo.** O auth já filtra tudo que o user-service rejeitaria. Mitigação: Decisão 2 (escolher e documentar um gatilho) ou aceitar demonstrar só por injeção de evento.
- **Falso positivo de `DUPLICATE_PROFILE`** (índice único por e-mail): o auth garante e-mail único, então o conflito só aparece em reentrega com `userId` novo, o que o auth não gera. Risco baixo, valor de demo também baixo.
- **Subscrição de `OrderConfirmed` sem efeito** consome mensagens e custo de ack à toa; remover da subscrição exige mexer no Terraform (`subscriptions`), fora deste escopo.

## 10. Unresolved questions

- Qual é o gatilho demonstrável de `UserCreationFailed` (Decisão 2)? Quem decide: Yasmin e Felipe, com o grupo, porque afeta o roteiro de apresentação.
- O grupo quer que a compensação apareça no roteiro F1–F8? Hoje ela não tem cenário numerado (ver visão geral, matriz).

## 11. Decisions

| # | Grade | Decision |
| --- | --- | --- |
| 1 | `LOCKED` | Falha de negócio na projeção do perfil vira `UserCreationFailed` via outbox com ack; erro transitório **nunca** compensa. |
| 2 | `OPEN` | Regra determinística que dispara `INVALID_PROFILE_DATA`. Opção recomendada: nome vazio após `trim` ou acima de `PROFILE_NAME_MAX_LENGTH` (o auth hoje corta em 100, então a regra só dispara por mensagem forjada/versão futura) **mais** um gatilho de demonstração documentado (por exemplo e-mail com domínio reservado `@invalid.ticketpro.test` rejeitado pelo user-service e aceito pelo auth). Alternativa: não ter gatilho e demonstrar publicando o evento à mão. |
| 3 | `OPEN` | Índice único em `profiles(lower(email))` (migration `V0005`) para ativar `DUPLICATE_PROFILE`. Benefício pequeno; só vale se a Decisão 2 não bastar. |
| 4 | `ASSUMED` | O handler recebe o `topicArn` e usa `OutboxWriter` diretamente; sem novo componente de plataforma. |
| 5 | `ASSUMED` | `OrderConfirmed` continua aceito sem efeito (mexer na subscrição é mudança de Terraform). |
| 6 | `ASSUMED` | `TicketsIssued` de usuário sem perfil é gravado. |

## 12. Phasing

- **P1:** teste de BOLA por HTTP + README (sem risco, fecha o item do roadmap).
- **P2:** decisão do gatilho (Decisão 2) e handler com `UserCreationFailed`.
- **P3:** `scripts/register-compensation.sh` junto com o plano do auth.
