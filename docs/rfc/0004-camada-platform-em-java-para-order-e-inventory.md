# RFC 0004 — Camada platform em Java para order e inventory

- **Status:** 📝 Draft (design fechado — implementação nas tarefas T01–T04 do [plano](../superpowers/plans/2026-11-19-java-order-inventory.md))
- **Scope:** O pacote `platform/` dos dois serviços Java que hoje são só scaffold (`order-service`, `inventory-service`): envelope, consumo SQS com ack após commit, outbox com relay e backoff, idempotência, retry/DLQ, jobs periódicos, dois roles de banco (app com RLS, worker com `BYPASSRLS`), health, logs e OTel. O ponto de partida é o `platform/` que o `auth-service` **já tem funcionando** (cópia com adaptações, não reescrita). Não cobre regra de negócio (RFC 0005 e 0006), formato do envelope (RFC 0001) nem a estratégia de falhas (RFC 0003). **Sem mudança de contrato** em `contracts/`.
- **Related:** [ADR-0005](../adr/0005-transactional-outbox.md), [ADR-0006](../adr/0006-idempotent-consumers-processed-messages.md), [ADR-0017](../adr/0017-vendored-platform-libraries-and-contracts.md), [ADR-0020](../adr/0020-database-roles-and-data-networks.md), [ADR-0021](../adr/0021-order-and-inventory-in-java.md), [ADR-0022](../adr/0022-java-platform-copied-from-auth-with-drift-check.md) (proposto), [RFC 0001](0001-message-contract-and-event-versioning.md), [RFC 0003](0003-failure-handling-strategy.md), [plano F1 — *Platform contract*](../superpowers/plans/2026-11-12-f1-platform.md), [`services/auth-service/.../platform/`](../../services/auth-service/src/main/java/br/furb/ticketpro/auth/platform/messaging/SqsConsumer.java), [`padroes.md` §2.2, §6, §7](../padroes.md)

---

## 1. Summary

Order e inventory não precisam de uma plataforma nova: o `auth-service` já implementa o *Platform contract* em Java (envelope, `SqsConsumer`, `OutboxRelay`, `ProcessedMessages`, `Backoff`, health, logs, testes P01–P16). Esta RFC define **o que copiar sem mudar, o que adaptar e o que falta**, para que os dois serviços ganhem a plataforma em horas, e não em dias.

Faltam três peças que o auth não precisou: um **runner do consumidor** (hoje `SqsConsumer.run` existe, mas nada o inicia), um **runner de jobs periódicos** (deadline scanner, expiração de reservas, retenção) e um **helper de transação com `app.user_id`** para a API com RLS. Há também dois defeitos a corrigir na cópia: o logger transforma `duplicate=true` em string (quebra o `f3-duplicate.sh`) e o tracing Java hoje não cria nenhum span.

## 2. Motivation

O ensaio com stack limpa falhou em 9 de 10 scripts (`happy-path.sh`, F1–F8): order e inventory sobem com `/health/*` e nada mais (sem `/events`, `POST /orders`, consumidores, outbox, saga). Todo requisito avaliado (R1, R2, R4, R6 e a saga) passa por esta camada, que é o caminho crítico: sem ela, as RFCs 0005 e 0006 não rodam. O freeze é **24/11 às 12h** e hoje é 19/11.

## 3. Current state

Verificado no código em 19/11:

- **order e inventory** (`services/*/src/main/java/.../`): só `*Application`, `HealthController` e `application.yml`. O `pom.xml` tem apenas `spring-boot-starter-webmvc` (Spring Boot 4.0.9, Java 21). Já existem `Dockerfile`, `Makefile`, `compose.yaml`, `.env.example`, `db.env.example`, `.devcontainer/`, `migrations/` (roles, outbox, processed_messages, domínio) e `contracts/` vendorizado.
- **auth-service** (`platform/`, ~1.500 linhas + 5 classes de teste):

| Pacote | Conteúdo | Observação verificada |
|---|---|---|
| `envelope` | `Envelope`, `EnvelopeCodec` (desembrulha SNS ou raw), `ContractValidator` (networknt, `contracts/events/<type>/v<N>`), exceções `Poison`/`InvalidMessage`/`UnsupportedVersion`/`Stale` | `messageId` é UUID v4 (JDK sem v7) |
| `messaging` | `SqsConsumer`, `MessageHandler`, `ConsumeOutcome`, `SnsOutboxPublisher`, `AwsClients` (retry do SDK = 1 tentativa) | `SqsConsumer` **não é iniciado por ninguém** (o auth ainda não consome); virtual thread por mensagem |
| `outbox` | `OutboxWriter.enqueue(Connection, topic, Envelope)`, `OutboxRelay` (claim `SKIP LOCKED` + lease, para na metade do lease, backoff), `OutboxRelayRunner` (`SmartLifecycle`) | sem limpeza de linhas publicadas |
| `idempotency` | `ProcessedMessages.record(Connection, consumer, messageId)` | sem limpeza |
| `retry`, `tracing` | `Backoff` (full jitter), `TraceContext` (W3C) | só `opentelemetry-api`: sem SDK/agente, **nenhum span é criado**; `inject()` devolve vazio e o `traceparent` do outbox fica `NULL` |
| `config` | `AppConfig` (+`Configuration`), `DatabaseUrl` | `AppConfig` mistura campos do auth (`Jwt`, `Argon2`, `AUTH_TOPIC_ARN`) |
| `health`, `logging`, `web` | `HealthController` (`broker: degraded` não derruba o ready), `StandardJsonLogFormatter`, `ApiExceptionHandler` (importa exceções do auth), `Requests` (validação de e-mail/senha) | o formatter faz `String.valueOf(kv.value)` nos pares de log |

- **Contradições com os scripts** (registradas, não decididas aqui):
  - `scripts/f3-duplicate.sh` faz `grep '"duplicate":true'`; o formatter atual emite `"duplicate":"true"` (string).
  - `.env.example` da raiz usa `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317`; o do inventory usa `otel-collector:4317` (sem esquema; o agente Java exige o esquema).
  - `CONTRACTS_DIR` é `/app/contracts` no order e `/contracts` no inventory, mas os dois `Dockerfile` fazem `COPY contracts /app/contracts`.
  - `ADR-0021` ("Consequências") ainda diz que order/inventory não têm `Dockerfile`/`Makefile`; o scaffold já os criou (ADR aceito não se edita; ver o plano, "Divergências").
  - `OUTBOX_RETENTION_H` e `PROCESSED_MESSAGES_RETENTION_D` aparecem nos ADR-0005/0006, mas não existem em nenhum serviço nem em `shared/`.

## 4. Goals / Non-goals

**Goals**

- Order e inventory com o mesmo comportamento observável das outras linguagens (R1, R6, F2, F3, F4, F6).
- Reuso máximo do código já testado do auth, com **uma checagem de deriva** barata entre as três cópias.
- Handlers de domínio **sem framework**: recebem `Envelope` + `Connection` e só traduzem para casos de uso.
- Nada de rede dentro de transação de banco; nenhum ack antes do commit.

**Non-goals**

- `shared/java` + `sync-shared.sh` (ADR-0017 só vendoriza Go/C#). Fica como saída se a deriva pesar ([ADR-0022](../adr/0022-java-platform-copied-from-auth-with-drift-check.md)).
- Migrar o `auth-service` para os novos runners nesta janela: ele só ganha o que for necessário para `UserCreationFailed` (dono: Felipe), depois do freeze das tarefas críticas.
- JPA/Spring Data/`@Transactional`: a TX do consumidor é explícita (ver Decisão 3).
- Throughput. O alvo é corretude sob concorrência e falha ([arquitetura §1](../arquitetura.md)).

## 5. Design

### 5.1 Estratégia: copiar o auth, adaptar, completar

Cada serviço tem `br.furb.ticketpro.<svc>.platform` (padrões §2.2). A primeira tarefa (T01) copia o `platform/` do auth trocando só o pacote, sem renomear classes, e roda `scripts/check-java-platform.sh` (novo, ver 5.10) para provar que a única diferença é o pacote. A partir daí, qualquer correção de plataforma entra nos três serviços no mesmo PR.

| Origem (auth) | Ação em order/inventory |
|---|---|
| `envelope/*`, `retry/Backoff`, `tracing/TraceContext`, `messaging/{MessageHandler,ConsumeOutcome,SnsOutboxPublisher,AwsClients,SqsConsumerProperties}`, `outbox/*`, `idempotency/*`, `config/DatabaseUrl`, `health/*`, `web/{ValidationException}` | copiar sem mudança |
| `messaging/SqsConsumer` | copiar; ajustes em 5.3 |
| `logging/StandardJsonLogFormatter` | copiar e **corrigir tipos** (5.6); o nome de serviço padrão deixa de ser `"auth-service"` |
| `config/AppConfig(+Configuration)` | **adaptar**: cada serviço tem o seu (variáveis de 5.8) |
| `web/ApiExceptionHandler` | **adaptar**: manter handlers genéricos (400, 404, 500, `ErrorResponse`) e mover os do auth para o módulo do auth |
| `web/Requests` | não copiar (é validação de cadastro) |
| — | **novo:** `messaging/SqsConsumerRunner`, `jobs/{PeriodicJob,PeriodicJobRunner}`, `db/UserScopedTransaction`, `outbox/RetentionJob`, `tracing/Spans` |

### 5.2 Envelope, validação e versões

Sem mudança de contrato. O `ContractValidator` carrega `CONTRACTS_DIR/events/**` no boot (falha se vazio) e rejeita `type/version` sem schema como `UnsupportedVersionException` (venenosa, RFC 0001 decisão 6). O handler declara as versões que aceita (`versions()`), e o **upcaster mora no handler**: `OrderCreated` v1 vira o modelo interno v2 (RFC 0006 §5.5). O envelope de saída usa `Envelope.create(correlationId, type, version, payload)`.

### 5.3 Consumo SQS (R1, R4, F2, F3, F4)

O algoritmo é o de [RFC 0003 §5.4](0003-failure-handling-strategy.md) e já está em `SqsConsumer.process`: valida → classifica o handler → abre TX no pool **worker** → `ProcessedMessages.record` → `handler.handle(envelope, connection)` → commit → **só então** `deleteMessage`. Resultados: `SUCCEEDED`, `DUPLICATE` (rollback + ack + `duplicate=true`), `STALE` (commit + ack + `stale_event`), `POISON` (rollback + `ChangeMessageVisibility(0)` → DLQ pelo redrive), `TRANSIENT` (rollback + visibilidade com backoff).

```java
public interface MessageHandler {            // já existe
  Set<Integer> versions();
  void handle(Envelope envelope, Connection connection) throws Exception;
}
// handler lança StaleEventException (sem efeito) | PoisonMessageException (nunca processável) | outra (transitória)
```

O que a cópia precisa ganhar:

1. **`SqsConsumerRunner implements SmartLifecycle`**, igual ao `OutboxRelayRunner`: uma virtual thread `sqs-consumer` roda `consumer.run(stop::get)`. No `stop()` o loop termina; mensagens em voo concluem (até `SHUTDOWN_TIMEOUT_S`, padrão 30, padrões §9), e o long poll de `SQS_WAIT_TIME_S` (10 s) limita o atraso. Sem ack, a mensagem volta para a fila.
2. **Pool worker dimensionado**: cada mensagem em voo segura uma conexão; `maximumPoolSize = CONSUMER_CONCURRENCY + 4` (relay, jobs, retenção).
3. **`BrokerProbe` composto**: `relay.brokerUp() && consumer.brokerUp()`; broker degradado não derruba o `/health/ready` (RFC 0003 §5.9).
4. **Erro de handler com SQL transitório** (`40001`, `40P01`, `08xxx`): o `catch (Exception)` atual já classifica como transitório; nada a mudar. Violação de constraint **depois de um efeito parcial** (ex.: `23505` em `reservations.order_id` depois do decremento do estoque) **propaga** como transitória: o rollback desfaz o efeito parcial e o reprocessamento cai no caminho `STALE`. Capturar e commitar deixaria o decremento órfão (RFC 0006 §5.3).

### 5.4 Outbox e relay (R6, F6)

`OutboxWriter.enqueue` na TX do chamador; `OutboxRelay` (claim `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING`, publica fora de TX, falha → `attempts+1` e `next_attempt_at` com full jitter até `OUTBOX_MAX_BACKOFF_MS`, uma linha de log por transição `broker_down`/`broker_up`). Sem mudança, exceto a **limpeza** (5.7).

### 5.5 Dois roles de banco (ADR-0020)

| Quem | DataSource | Role | RLS |
|---|---|---|---|
| Controllers HTTP | `appDataSource` (`@Primary`, `DATABASE_URL`) | `<svc>_app` | aplicada |
| Consumidor, relay, jobs, retenção | `workerDataSource` (`DATABASE_WORKER_URL`) | `<svc>_worker` | `BYPASSRLS` |

Novo helper para a API (o auth não precisou: só usa o worker):

```java
public final class UserScopedTransaction {
  /** BEGIN; SELECT set_config('app.user_id', ?, true); fn(connection); COMMIT. Rollback em erro. */
  public static <T> T run(DataSource app, UUID userId, SqlFunction<Connection, T> fn) throws SQLException;
}
```

`X-User-Id` ausente ou que não é UUID → 401, antes de abrir conexão. Regra de revisão: **import de `workerDataSource` em controller é bug** (a checagem de T03 procura isso). As tabelas `outbox` e `processed_messages` não têm RLS; o role `app` as escreve por `ALTER DEFAULT PRIVILEGES` (V0001).

### 5.6 Logs

Cópia do `StandardJsonLogFormatter` com **valores tipados**: `Boolean` e `Number` dos pares chave-valor saem como JSON boolean/number (`"duplicate":true`, `"receiveCount":2`), e o resto como string. É o que `f3-duplicate.sh` espera. Logs obrigatórios: `message_published`, `message_consumed`, `duplicate=true`, `poison=true`, `message_rejected`, `stale_event`, `broker_down`/`broker_up`, `compensation_applied`, `late_payment_refund` (padrões §5).

### 5.7 Jobs periódicos e retenção

```java
public interface PeriodicJob {
  String name();
  Duration interval();                // ORDER_DEADLINE_SCAN_INTERVAL_MS, RESERVATION_EXPIRY_SCAN_INTERVAL_MS…
  int batchSize();
  int runOnce() throws Exception;     // uma TX no pool worker; devolve quantas linhas processou
}
```

`PeriodicJobRunner` (`SmartLifecycle`, uma virtual thread por job): chama `runOnce`; se `n == batchSize`, repete (com `*_BATCH_PAUSE_MS` quando existir); senão dorme `interval`. Exceção: loga `job_failed` (nome do job + erro) e dorme `Backoff.delay(falhas)`, **sem derrubar o processo**. Seguro em N réplicas porque cada job usa `FOR UPDATE SKIP LOCKED`. `RetentionJob` apaga em lotes `outbox` publicados há mais de `OUTBOX_RETENTION_H` (padrão 24) e `processed_messages` mais antigos que `PROCESSED_MESSAGES_RETENTION_D` (padrão 7). É o primeiro item cortável (Decisão 8).

### 5.8 Configuração (padrões §8)

`AppConfig.from(env)` por serviço, falhando no boot com o nome da variável. Comuns: `SERVICE_NAME`, `PORT`, `LOG_LEVEL`, `DATABASE_URL`, `DATABASE_WORKER_URL`, `AWS_REGION`, `AWS_ENDPOINT_URL`, `HTTP_CLIENT_TIMEOUT_MS`, `OUTBOX_*`, `QUEUE_URL`, `CONTRACTS_DIR`, `CONSUMER_CONCURRENCY`, `SQS_WAIT_TIME_S`, `SHUTDOWN_TIMEOUT_S`. Específicas: order → `ORDERS_TOPIC_ARN`, `ORDER_*`, `ORDER_CREATED_VERSION` (1|2); inventory → `INVENTORY_TOPIC_ARN`, `RESERVATION_*`. **Os dois validam** `RESERVATION_TTL_MS >= ORDER_PAYMENT_TIMEOUT_MS + RESERVATION_SAFETY_MARGIN_MS` no boot, com mensagem que nomeia as variáveis (RFC 0003 decisão 4).

### 5.9 Observabilidade (ADR-0012)

Hoje só a **propagação** existe. Para o trace `POST /orders → TicketsIssued` (bônus F4) faltam spans. Plano mínimo: **agente OpenTelemetry Java** no `Dockerfile` (versão e sha256 fixados) para HTTP e JDBC, mais `tracing/Spans` (3 métodos sobre `GlobalOpenTelemetry`): span `consumer` por mensagem, no trace do produtor e com link, e span `producer` por publicação do relay, que grava o `traceparent` real no outbox. Sem `OTEL_EXPORTER_OTLP_ENDPOINT`, nada é exportado e nada falha. Fica **depois** da corretude (T14); a corretude não depende dele.

### 5.10 Checagem de deriva

`scripts/check-java-platform.sh` compara `platform/` de auth, order e inventory **depois de normalizar o pacote** (`sed 's/\.\(auth\|order\|inventory\)\./.<svc>./g'`) e lista os arquivos divergentes. Os arquivos *adaptados* (`AppConfig*`, `ApiExceptionHandler`) ficam numa lista de exceções no próprio script. Roda no `make lint` da raiz ou no CI da raiz (`root.yml`). Barato (≈30 linhas) e suficiente para o ADR-0022.

### Alternatives considered

- **`shared/java` vendorizado (ADR-0017).** Uniforme, mas exige reescrita de pacote no `sync-shared.sh`, migrar o auth (que funciona) e três módulos para testar. Perde por custo/risco a cinco dias do freeze.
- **Reescrever a plataforma por serviço sem partir do auth.** Repete trabalho já testado (P01–P16) justamente nos pontos que valem nota.
- **Spring Integration / Spring Cloud AWS (`@SqsListener`).** Esconde a fronteira de TX e o ack, que aqui é o requisito (R1). Perde.

## 6. Tests

Cada serviço roda a matriz do auth com o mesmo ID no nome do teste: `p01`–`p05` (core), `p07`–`p16` (integração Testcontainers: duplicata ×5, crash antes do commit, poison→DLQ, stale, SNS e raw, relay com broker fora, relays concorrentes, lease). Novos: `p17` runner inicia/para sem perder mensagem em voo; `p18` formatter emite `duplicate` como boolean; `p19` `UserScopedTransaction` + RLS (usuário B não lê o pedido de A); `p20` `PeriodicJobRunner` sobrevive a exceção e drena lote cheio; `p21` retenção apaga só o que passou do corte; `p22` `check-java-platform.sh` falha com uma classe divergente. `make test test-integration lint typecheck` por serviço.

## 7. Docs

README de cada serviço (comandos, variáveis, eventos); `docs/padroes.md` §2.2 ganha uma linha apontando para esta RFC; `docs/rfc/INDEX.md`. As divergências da seção 3 entram no plano (seção "Divergências"). O runbook só muda se o roteiro mudar.

## 8. Out of scope

- Exactly-once de transporte (RFC 0003 §4).
- Redrive automático de DLQ (manual, `scripts/dlq.sh`).
- Métricas Prometheus. A observabilidade da demo é log JSON + Jaeger.
- Trocar o polling do outbox por CDC (arquitetura §12).

## 9. Risks

- **Três cópias divergem.** Mitigação: checagem de deriva (5.10) e regra "correção de plataforma entra nos três no mesmo PR". Aceito até 02/12.
- **Cópia leva junto um defeito do auth.** O logger já é um caso (5.6). Mitigação: `p17`–`p22` rodam nos três; o auth recebe a correção.
- **Pool worker esgotado** com `CONSUMER_CONCURRENCY` alto: mensagens esperam conexão e o visibility timeout vence. Mitigação: tamanho do pool derivado da concorrência (5.3) e `SQS_VISIBILITY_TIMEOUT_S` > p99 (RFC 0003 §5.1).
- **Agente OTel aumenta a imagem e o boot** (F4). Mitigação: é opcional e cortável; a corretude não depende dele.
- **Relógio do banco × JVM.** Prazos (`payment_deadline_at`, `expires_at`) usam `now()` do Postgres, nunca `Instant.now()` da JVM nas condições do `WHERE`.

## 10. Unresolved questions

- **Cada serviço Java também publica `traceparent` quando o agente não está presente?** Hoje o campo fica `NULL`; sem consequência funcional. Resolve-se em T14.
- **O auth adota os runners novos?** Decisão do Felipe depois do freeze das tarefas críticas; só é obrigatória se o `UserCreationFailed` (F3 do cadastro) entrar na demo.
- **Valores finais de `SQS_VISIBILITY_TIMEOUT_S` e concorrência** (RFC 0003 decisão 9): calibrar medindo o p99 no `happy-path.sh` com stack limpa.

## 11. Decisions

| # | Grade | Decision |
|---|---|---|
| 1 | `LOCKED` | Os três serviços Java compartilham o **mesmo contrato de platform** (classes e comportamento do `auth-service`, cada um no seu pacote `br.furb.ticketpro.<svc>.platform`). Mudar o contrato afeta três serviços e a matriz de testes. |
| 2 | `ASSUMED` | A reutilização é **cópia com checagem de deriva**, sem `shared/java` ([ADR-0022](../adr/0022-java-platform-copied-from-auth-with-drift-check.md), proposto). Se o grupo preferir `shared/java`, só as seções 5.1 e 5.10 mudam. |
| 3 | `LOCKED` | Acesso a dados em **JDBC puro**: o handler recebe a `Connection` da TX do consumidor. Sem `@Transactional`, JPA nem Spring Data na plataforma e nos handlers. Mantém `processed_messages` + efeito + outbox visivelmente numa só TX. |
| 4 | `LOCKED` | Controllers HTTP usam só o pool `app` com `set_config('app.user_id', …, true)`; consumidor, relay, jobs e retenção usam só o pool `worker`. |
| 5 | `ASSUMED` | O logger emite booleanos e números tipados nos pares chave-valor (`"duplicate":true`), por exigência do `f3-duplicate.sh`. |
| 6 | `ASSUMED` | `SqsConsumerRunner` e `PeriodicJobRunner` são `SmartLifecycle` com virtual threads, no mesmo molde do `OutboxRelayRunner`. |
| 7 | `ASSUMED` | `messageId` e IDs de domínio são UUID v4 (o JDK não tem v7; RFC 0001 decisão 8 e os schemas aceitam os dois). |
| 8 | `ASSUMED` | A retenção usa `OUTBOX_RETENTION_H=24` e `PROCESSED_MESSAGES_RETENTION_D=7`. É a primeira tarefa a cortar sob pressão de prazo; o custo é só crescimento de tabela na demo. |
| 9 | `OPEN` | Estratégia de tracing: agente OTel Java (HTTP/JDBC) + spans manuais de consumer/producer. Quem implementar T14 pode trocar por SDK embutido se o agente não funcionar contra o Collector, e registra o motivo. |
| 10 | `OPEN` | Tamanho do pool worker e `SQS_VISIBILITY_TIMEOUT_S` finais (ponto de partida: concorrência + 4 e 30 s). |

## 12. Phasing

- **P1 (T01–T02):** cópia do auth, `AppConfig`, DataSources, runners, health, logger corrigido, `p01`–`p18` verdes nos dois serviços.
- **P2 (T03):** `UserScopedTransaction`, `PeriodicJobRunner`, `check-java-platform.sh`.
- **P3 (T04, cortável):** `RetentionJob`.
- **P4 (T14, bônus):** agente OTel e spans.
