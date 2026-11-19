# Order e Inventory em Java: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (ou subagent-driven-development) para executar tarefa a tarefa, e superpowers:test-driven-development em cada uma. **Este plano só descreve trabalho: nenhum código de produção, migration ou teste foi escrito ao produzi-lo.**

**Goal:** Fazer o `order-service` e o `inventory-service` (hoje só scaffold de build, ADR-0021) cumprirem R1–R6 e os cenários F1–F8, de modo que `scripts/happy-path.sh` e `scripts/f1..f8` passem numa stack limpa até o **freeze de ter 24/11 às 12h** (apresentação qua 25/11).

**Architecture:** Três camadas, nesta ordem. (1) `platform/` copiada do `auth-service` (que já a tem testada) com três peças novas: runner do consumidor, runner de jobs e transação com `app.user_id` ([RFC 0004](../../rfc/0004-camada-platform-em-java-para-order-e-inventory.md), [ADR-0022](../../adr/0022-java-platform-copied-from-auth-with-drift-check.md) proposto). (2) inventory: catálogo, reserva atômica, ingressos, expiração ([RFC 0006](../../rfc/0006-design-do-inventory-service-em-java.md)). (3) order: API, saga, deadline scanner, estorno tardio ([RFC 0005](../../rfc/0005-design-do-order-service-em-java.md)).

**Tech Stack:** Java 21, Spring Boot 4.0.9 (Jackson 3, `tools.jackson`), JDBC puro, AWS SDK v2 (SNS/SQS), networknt json-schema-validator, OpenTelemetry API, JUnit 5 + Testcontainers (Postgres 16, MiniStack `ministackorg/ministack:1.5.40`). Versões iguais às do `auth-service/pom.xml` (ADR-0018).

**Spec:** RFCs 0004, 0005, 0006; [RFC 0003 §5](../../rfc/0003-failure-handling-strategy.md); [arquitetura §6, §9, §11](../../arquitetura.md); [padrões](../../padroes.md) §2.2, §4, §6, §7, §11; [roadmap](../../roadmap.md) F2–F5; contratos em [`contracts/`](../../../contracts).

## Global Constraints

- **Branch:** uma branch normal por tarefa, `feat/<serviço>-<descrição>` (ex.: `feat/order-service-create-order`). Nunca commitar na `main`, sem push sem pedir, sem git worktree. Commits Conventional com escopo da pasta (`feat(inventory-service): reserve stock atomically`).
- **Autonomia (ADR-0016):** nada de `../` em código, `Dockerfile` ou `pom.xml`. A cópia do `platform/` entra em cada serviço; não há módulo compartilhado.
- **Migrations:** as V0001–V0007 existentes **não se editam**. Qualquer mudança de schema é `V<next>__*.sql` (nenhuma é prevista; a Decisão 5 do RFC 0005 é a única candidata).
- **Contratos:** nenhuma mensagem nova ou alterada. Todo evento produzido valida contra `contracts/examples` (`make -C contracts check` continua verde).
- **Env, não hardcode:** prazos, taxas, filas e tópicos vêm de env, validados no boot (padrões §8). Env nova entra no `.env.example` do serviço.
- **Definition of Done de toda tarefa:** `cd services/<svc> && make lint typecheck test test-integration` verde nos serviços tocados; o script de validação da tarefa com exit 0 (quando listado); README do serviço atualizado se mudou API/env/evento; relatar falhas, não escondê-las (CLAUDE.md).
- **Ownership:** order = Matheus, inventory = Felipe (ADR-0021). Plataforma: Matheus e Felipe juntos. Revisão cruzada obrigatória. Duas pessoas nunca editam a mesma pasta de serviço ao mesmo tempo.

## Escopo e corte até o freeze

| Faixa | Tarefas | Se o prazo apertar |
|---|---|---|
| **Must** (núcleo avaliado) | T01, T02, T03, T05, T06, T07, T09, T10, T11, T12 | Nunca cortar. Sem elas, R1–R3, R6 e F1–F8 não fecham. |
| **Should** | T08 (expirer), T13 (demo v1/v2/v3) | T13 é só verificação (o upcaster nasce em T06). T08 é rede de segurança: cortar por último e documentar no README. |
| **Could** | T04 (retenção), T14 (OTel Java) | Cortar primeiro: T04, depois T14. O custo de T04 é só tabela crescendo; T14 é bônus (roadmap F4 #3). |

**Linha de corte (alinhada ao roadmap):** se `happy-path.sh` e F1–F8 não estiverem verdes até **dom 22/11 às 12h**, T14 e T04 são cortadas, e T13 e T08 só continuam se sobrarem horas. Seg 23/11 é só correção, ensaio e documentação; ter 24/11 até 12h, só bug bloqueante.

### Cronograma

| Dia | Matheus (order + plataforma) | Felipe (inventory) | Checkpoint |
|---|---|---|---|
| **qui 19/11 (resto do dia)** | T01 (order) | T01 (inventory) | `make lint typecheck test` verde nos dois; `docker compose up`: os dois `healthy` com `/health/ready` mostrando `db` |
| **sex 20/11** | T02 → T03 → T09 | T05 → T06 (após T02) | T02 e T03 mergeadas ao meio-dia; `GET /events` e `POST /orders` 202 pelo gateway; R2 verde |
| **sáb 21/11** | T10 | T07 | **`happy-path.sh` verde**, F1, F3, F4, F5 (noite) |
| **dom 22/11 até 12h** | T11 | T08 | **Linha de corte: F1–F8 verdes** (T11 fecha F6/F7) |
| dom 22/11 tarde | T12 (com Pedro) | T13 | Ensaio completo na stack limpa |
| **seg 23/11** | T14 (se passou a linha) / T04 / correções | idem | Trace no Jaeger (bônus) |
| **ter 24/11 até 12h** | Freeze: só bloqueantes | idem | Ensaio cronometrado `git clone → compose up → roteiro` |

### Dependências

```
T01 ─┬─ T02 ─┬─ T06 ── T07 ──────────┐
     │       └─ T10 ── T11 ──────────┤
     ├─ T03 ─┬─ T09 ─ T10            ├─ T12 ── (T13, T14)
     │       ├─ T08 (após T06)       │
     │       └─ T04                  │
     └─ T05 ─────────────────────────┘
```

(T08 e T11 dependem de T03 pelo `PeriodicJobRunner`; T09 depende de T03 pelo `UserScopedTransaction`; T10 depende de T09 e T02; T13 depende de T06 e T09.)

## Mapa requisito/cenário → tarefa → script

| Requisito/cenário | Tarefas que o cumprem | Script que valida |
|---|---|---|
| **R1** at-least-once + idempotência | T02 (p09, p10, p14), T06, T10 | `f3-duplicate.sh`, `f2-consumer-kill.sh` |
| **R2** zero venda dupla | T06 (teste de 100 threads), T10 | `f5-race.sh` |
| **R3** API assíncrona | T09 (+ T10: o status evolui) | `happy-path.sh` |
| **R4** consumidores concorrentes | T02, T06, T10 (2 réplicas na mesma fila) | `check-replicas.sh 3`, `f8-scale.sh` |
| **R5** dependência instável | payment-service (C#, já pronto); order trata o resultado em T10/T11 | `f1-compensation.sh`, `f7-late-response.sh` |
| **R6** reconexão automática | T02 (p12), T09 (aceita pedido com broker fora), T11 | `f6-broker-down.sh` |
| **F1** compensação | T10 (`PaymentFailed`), T07 (devolve estoque) | `f1-compensation.sh` |
| **F2** kill de consumidor | T02, T06 | `f2-consumer-kill.sh` |
| **F3** duplicata ×5 | T02, T06, T03 (log tipado em T01) | `f3-duplicate.sh` |
| **F4** mensagem venenosa | T02, T06 | `f4-poison.sh` |
| **F5** 100 pedidos × 10 unidades | T06, T10 | `f5-race.sh` |
| **F6** broker fora 30 s | T02, T09, T11 (deadline scanner) | `f6-broker-down.sh` |
| **F7** resposta tardia | T11 | `f7-late-response.sh` |
| **F8** escala ×3 | T10, T11 (sem duplicidade), T12 | `f8-scale.sh` |
| Bônus versionamento | T06 (upcaster), T09 (`ORDER_CREATED_VERSION`), T13 | `demo-versioning.sh` |
| Bônus OTel | T14 | trace no Jaeger (`http://localhost:16686`) |

## Divergências encontradas (registradas, não decididas aqui)

Contradições entre documentos, contratos, scripts e código. Quem decide é o grupo (ou o dono do artefato); onde a RFC assumiu um comportamento, a coluna "Tratamento no plano" diz qual.

| ID | O que diverge | Impacto | Tratamento no plano |
|---|---|---|---|
| D1 | `InventoryRejected.reason` aceita `SECTOR_NOT_FOUND` e `INVALID_QUANTITY`; `OrderCancelled.reason` e `orders.cancel_reason` só têm `OUT_OF_STOCK`, `PAYMENT_FAILED`, `PAYMENT_TIMEOUT`, `RESERVATION_EXPIRED` | Sem valor correto para esses dois cancelamentos | RFC 0005 Decisão 4 assume `OUT_OF_STOCK` (como o código antigo). **Decisão do grupo pendente**: estender o enum exige bump de contrato (RFC 0001 §5.4) |
| D2 | `f3-duplicate.sh` busca `"duplicate":true`; o `StandardJsonLogFormatter` do auth emite string (`String.valueOf`) | F3 falharia em Java | T01 corrige o formatter e testa (p18); o auth recebe a correção |
| D3 | `OTEL_EXPORTER_OTLP_ENDPOINT`: raiz `http://otel-collector:4317`, inventory `otel-collector:4317` (sem esquema) | Agente Java ignora/rejeita endpoint sem esquema | T14 (e T01 padroniza o `.env.example` do inventory) |
| D4 | `CONTRACTS_DIR`: order `/app/contracts`, inventory `/contracts`; os dois `Dockerfile` copiam para `/app/contracts` | Inventory não carregaria os schemas no boot | T01 alinha o `.env.example` do inventory |
| D5 | ADR-0021 ("Consequências") e os READMEs de order/inventory dizem que não há `Dockerfile`/`Makefile` e que o compose não builda; o scaffold já os criou. O README do inventory ainda cita o healthcheck do binário Go | Documentação desatualizada | ADR aceito não se edita: o ADR-0022 registra o estado; os READMEs são corrigidos em T01 |
| D6 | ADR-0005/0006 prometem `OUTBOX_RETENTION_H` e `PROCESSED_MESSAGES_RETENTION_D`; nenhum serviço nem `shared/` os implementa | Tabelas crescem | T04 (Could); documentado como gap no README |
| D7 | Roadmap F2/F3 ainda lista Pedro como dono do inventory (Go); ADR-0021 passou para Felipe | Donos inconsistentes | Este plano usa ADR-0021; ajustar o roadmap na T12 |
| D8 | `orders`: V0004 e V0006 criam o mesmo índice parcial com nomes diferentes | Índice duplicado (custo mínimo de escrita) | Não editar migration aplicada; opcional `V0007__drop_duplicate_deadline_index.sql` em T11 |
| D9 | Padrões §6 prefere UUID v7; o JDK só gera v4 | Nenhum (schemas aceitam v4, RFC 0001 decisão 8) | RFC 0004 Decisão 7 assume v4 |
| D10 | O `orders` não guarda `correlation_id`; scanner e consumidores geram UUID novo | Trace do cancelamento por timeout solto | RFC 0005 Decisão 5 (`OPEN`), decidir em T11 |

## Riscos

| # | Risco | Prob. | Mitigação |
|---|---|---|---|
| 1 | **Prazo:** 5 dias, 2 devs, plataforma + dois serviços | Alta | Faixas Must/Should/Could e linha de corte de dom 22/11; plataforma copiada, não escrita; T01 em paralelo (um serviço cada) |
| 2 | **Defeitos herdados do auth** na cópia (D2 já é um) | Média | Testes p01–p22 rodam nos três; correção entra nos três no mesmo PR; `check-java-platform.sh` (T03) |
| 3 | **Corrida deadline × `PaymentApproved`** (F7) | Média | Decisão no banco (`UPDATE` condicional); IT de duas threads (T11) |
| 4 | **Reserva órfã** se o erro vem depois do decremento | Média | RFC 0006 §5.3 (rollback propagado) e IT dedicado (T06) |
| 5 | **Memória do ensaio:** ~20 containers + 3 JVMs com 2 réplicas cada (`-XX:MaxRAMPercentage=75`) | Média | Medir em T12; ajustar `JAVA_TOOL_OPTIONS`/limites de memória no `compose.yaml`; imagens `jre-alpine` já usadas |
| 6 | **Build lento** (Maven sem cache em máquina limpa, ensaio cronometrado) | Média | `dependency:go-offline` já está no `Dockerfile`; medir em T12 |
| 7 | **Pool de conexões worker esgotado** → visibility timeout vence | Baixa | `maximumPoolSize = CONSUMER_CONCURRENCY + 4`; p17/F5 |
| 8 | **MiniStack ≠ AWS** (redrive, visibility) | Baixa | Já validado nos spikes (S1–S7); ITs de plataforma rodam contra o MiniStack |
| 9 | **Conflito entre T01 dos dois serviços** e reviews em cadeia | Média | Cada pessoa toca só a sua pasta; `check-java-platform.sh` prova que as cópias são iguais |
| 10 | **Spring Boot 4 / Jackson 3** (APIs novas, pouca documentação) | Baixa | Copiar do auth (já compila e testa com a mesma versão) |
| 11 | **Pedido preso em `PENDING`** se a mensagem do inventory for à DLQ | Baixa | Fora do escopo (RFC 0005 §8); `scripts/dlq.sh` mostra e reprocessa |

## Review Focus

1. **R2 com 2 réplicas:** 100 threads × 10 unidades resultam em exatamente 10 reservas e `available = 0` (T06), e `f5-race.sh` fecha com 10 `CONFIRMED` + 90 `OUT_OF_STOCK`.
2. **Ack depois do commit:** nenhum handler dá ack; só `SqsConsumer`. Erro depois de efeito parcial faz rollback (RFC 0006 §5.3).
3. **Roles:** nenhum controller importa `workerDataSource`; nenhum consumidor/job usa `appDataSource`; BOLA devolve 404 (T09).
4. **Transições:** todo `UPDATE` de saga tem `WHERE status = <esperado>`; `payment_deadline_at` e `expires_at` vêm do `now()` do banco.
5. **Liberação única:** cancelamento, expiração e confirmação concorrentes devolvem o estoque no máximo uma vez (T07, T08).
6. **Log tipado:** `"duplicate":true` e `"message_rejected"` aparecem como os scripts esperam (T01).

---

## Tarefas

### T01: Plataforma copiada do auth, config, DataSources e health (order e inventory)

- **Owner / paralelismo:** Matheus (order) e Felipe (inventory), em paralelo, cada um na sua pasta. **Depende de:** nada.
- **Files:** `services/{order,inventory}-service/pom.xml` (deps do auth: jdbc, postgresql, AWS SDK BOM + sns + sqs, networknt, opentelemetry-api, testcontainers-postgresql; plugins do auth), `src/main/java/.../platform/**` (cópia do auth, só o pacote muda), `AppConfig` + `AppConfigConfiguration` próprios, `application.yml` (formato de log JSON apontando para o pacote do serviço), `messages.properties` (PT-BR), `.env.example` (`SHUTDOWN_TIMEOUT_S`, `CONTRACTS_DIR=/app/contracts` no inventory, endpoint OTLP com esquema), READMEs (D4, D5).
- **Produces:** pacotes `envelope`, `messaging`, `outbox`, `idempotency`, `retry`, `tracing`, `config`, `health`, `logging`, `web`; dois `HikariDataSource` (`@Primary` app, `@Qualifier("worker")`); `AppConfig.from(env)` com validação no boot, **incluindo a regra `RESERVATION_TTL_MS >= ORDER_PAYMENT_TIMEOUT_MS + RESERVATION_SAFETY_MARGIN_MS`** nos dois (RFC 0003 decisão 4); formatter com valores tipados (D2).
- **Passos (TDD):**
  - [ ] Copiar `platform/` do auth, trocar o pacote e **não renomear classes**; remover o que é do auth (`Jwt`, `Argon2`, `AUTH_TOPIC_ARN`, exceções do cadastro).
  - [ ] Testes primeiro: `AppConfigTest` (variável ausente/ inválida nomeia a variável; TTL 40000 com timeout 15000 e margem 30000 falha citando as três), `HealthControllerTest` (db down → 503; broker degradado → 200), `StandardJsonLogFormatterTest` + **p18** (`duplicate` sai como boolean), `PlatformCoreTest` p01–p05 contra `services/<svc>/contracts`.
  - [ ] Implementar `AppConfig` por serviço e o formatter corrigido; ligar `BrokerProbe` ao relay (o consumidor entra em T02).
  - [ ] Commit em dois: `feat(order-service): add platform layer ported from auth-service`, `feat(inventory-service): ...`; correção do formatter também no auth: `fix(auth-service): emit typed log key-values`.
- **Pronto quando:** `make lint typecheck test` verde nos dois; `docker compose up -d --build order-service inventory-service` com ambos `healthy` e `/health/ready` com `{"db":"up","broker":"up"}`; serviço **não sobe** com `DATABASE_URL` inválida ou TTL inconsistente e o log nomeia a variável.
- **Valida:** `scripts/check-autonomy.sh services/order-service` e `…/inventory-service`; `scripts/verify-platform.sh`; `scripts/wait-healthy.sh 180`.
- **Mapeia:** F3 (log, D2); base de R1/R6.

### T02: Runner do consumidor, relay no boot e testes de integração da plataforma

- **Owner:** Matheus (feito em order; Felipe copia para inventory na mesma PR ou em seguida). **Depende de:** T01.
- **Files:** `platform/messaging/SqsConsumerRunner.java` (`SmartLifecycle`, virtual thread, para no `SHUTDOWN_TIMEOUT_S`), wiring do `SqsConsumer` e do mapa de handlers (vazio até T06/T10) em `AppConfigConfiguration`, `BrokerProbe` composto (`relay && consumer`), `HikariDataSource` do worker com `maximumPoolSize = CONSUMER_CONCURRENCY + 4`, `src/test/.../platform/{PlatformIT,support/Containers}.java` copiados do auth.
- **Passos:**
  - [ ] Portar `PlatformIT` (p07–p16: record, outbox na TX, relay com broker pausado e drenando, relays concorrentes, lease, duplicata ×5, crash antes do commit, poison→DLQ, stale, SNS e raw) rodando contra Testcontainers (Postgres + MiniStack).
  - [ ] Novo **p17**: o runner inicia e para sem perder mensagem em voo (a mensagem sem ack volta para a fila).
  - [ ] Implementar o runner e o wiring.
- **Pronto quando:** `make test-integration` verde nos dois serviços com p07–p17; no compose, logs mostram o consumidor ligado sem erro com o mapa de handlers vazio (mensagens desconhecidas vão para a DLQ, nunca ficam presas).
- **Valida:** ITs (Testcontainers); `scripts/verify-platform.sh`. (Os scripts F2–F4 só fecham com o handler de T06.)
- **Mapeia:** R1, R4, R6; base de F2, F3, F4.

### T03: Transação com `app.user_id`, runner de jobs e checagem de deriva

- **Owner:** Matheus. **Depende de:** T01.
- **Files:** `platform/db/UserScopedTransaction.java`, `platform/jobs/{PeriodicJob,PeriodicJobRunner}.java`, `scripts/check-java-platform.sh` (+ `scripts/tests/check-java-platform.test.sh`), `.github/workflows/root.yml` (passo novo, se o workflow da raiz já roda os scripts de `scripts/`).
- **Passos:**
  - [ ] Testes: **p19** (`UserScopedTransaction` + RLS: o usuário B não lê o que o A gravou, sem `WHERE user_id` no SQL), **p20** (runner sobrevive a exceção, loga `job_failed`, repete enquanto o lote vier cheio, para limpo), **p22** (o script falha com uma classe divergente e passa com as três árvores iguais).
  - [ ] Implementar; a mesma PR copia os arquivos para o inventory (o script prova a igualdade).
- **Pronto quando:** p19, p20 e p22 verdes; `scripts/check-java-platform.sh` exit 0 com auth, order e inventory.
- **Valida:** `scripts/check-java-platform.sh`; `scripts/tests/check-java-platform.test.sh`.
- **Mapeia:** BOLA (RFC 0002 §5.6), base de F1/F7 (scanner e expirer).

### T04 (Could): Retenção de `outbox` e `processed_messages`

- **Owner:** Matheus. **Depende de:** T03.
- **Passos:** [ ] **p21**: o job apaga em lotes apenas o que passou de `OUTBOX_RETENTION_H` (24) e `PROCESSED_MESSAGES_RETENTION_D` (7), e nunca linhas não publicadas. [ ] `RetentionJob implements PeriodicJob`, ligado nos dois serviços.
- **Pronto quando:** p21 verde; `.env.example` com as duas variáveis. **Valida:** IT. **Mapeia:** ADR-0005/0006, D6. **Cortar primeiro.**

### T05: Inventory, catálogo (`GET /events`, `GET /events/{id}`)

- **Owner:** Felipe. **Depende de:** T01.
- **Files:** `modules/catalog/{web,application,infrastructure}/**`, `CursorCodec`, testes. Formato idêntico ao do serviço antigo (RFC 0006 §5.2).
- **Passos:** [ ] IT com Postgres e o seed real (`migrations/V0005`): lista paginada por keyset, `limit` > 50 → 400, cursor inválido → 400, `id` inexistente/inválido → 404, sem `X-User-Id` → 401. [ ] Implementar com o pool **app**.
- **Pronto quando:** testes verdes; pelo gateway, `GET /events` devolve `.items[0].id` e `GET /events/{id}` devolve `.sectors[] {id, available}`.
- **Valida:** os passos 3 do `scripts/happy-path.sh` (executar o script até ali; o `POST /orders` só passa depois de T09/T10).
- **Mapeia:** R3 (catálogo).

### T06: Inventory, reserva atômica, upcaster e R2

- **Owner:** Felipe. **Depende de:** T02 (consumidor) e T05 (só para o `GET /events` do script).
- **Files:** `modules/reservations/{application/ReserveStock,domain/{OrderCreatedV2,Upcaster},infrastructure/ReservationRepository}.java`, handler `OrderCreated` registrado com `versions() = {1, 2}`, testes.
- **Passos (RFC 0006 §5.3–5.4, 5.9):**
  - [ ] **Unitário:** upcaster (v1 → `channel = "API"`, v2 mantém); `quantity < 1` → `INVALID_QUANTITY`.
  - [ ] **IT R2:** 100 threads × setor de 10 → **exatamente 10** reservas, `available = 0`, 90 `InventoryRejected(OUT_OF_STOCK)`, nenhum `CHECK` violado.
  - [ ] **IT idempotência:** mesma mensagem ×5 → 1 reserva e 1 `processed_messages`; duas mensagens com `messageId` diferentes para o mesmo `orderId` → 1 reserva e estoque certo (rollback do decremento, §5.3).
  - [ ] **IT contrato:** `InventoryReserved`/`InventoryRejected` produzidos validam contra `contracts/examples`; `examples/invalid` viram `POISON`.
  - [ ] Implementar (passo 0 `SELECT`, `UPDATE` condicional, `INSERT … RETURNING expires_at`, outbox).
- **Pronto quando:** testes verdes; stack no ar: `f3-duplicate.sh`, `f4-poison.sh`, `f2-consumer-kill.sh` com exit 0 (usam só o inventory).
- **Valida:** `scripts/f3-duplicate.sh`, `scripts/f4-poison.sh`, `scripts/f2-consumer-kill.sh`.
- **Mapeia:** **R2**, R1, R4, F2, F3, F4, bônus versionamento.

### T07: Inventory, confirmação com ingressos e devolução de estoque

- **Owner:** Felipe. **Depende de:** T06.
- **Files:** `modules/tickets/{application/IssueTickets,domain/TicketCode}.java`, `modules/reservations/application/ReleaseStock.java`.
- **Passos (RFC 0006 §5.5–5.6):**
  - [ ] Testes: `TicketCode` casa `^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$`; `OrderConfirmed` → `CONFIRMED` + N ingressos + `TicketsIssued` válido; reserva `RELEASED` → `STALE` + log ERROR `order_confirmed_after_release`; `OrderCancelled` ×N, após expiração e após confirmação → estoque devolvido ≤ 1 vez.
  - [ ] Implementar os dois handlers.
- **Pronto quando:** testes verdes; `f1-compensation.sh` mostra `available` voltando e reserva `RELEASED` (precisa de T10 para o pedido chegar a `CANCELLED`; antes disso, o IT cobre o handler).
- **Valida:** ITs; `scripts/f1-compensation.sh` (após T10).
- **Mapeia:** F1, `TicketsIssued` (2+ consumidores: user e notification).

### T08 (Should): Inventory, job de expiração de reservas

- **Owner:** Felipe. **Depende de:** T03, T06.
- **Files:** `modules/reservations/application/ReservationExpirer.java` (`PeriodicJob`).
- **Passos (RFC 0006 §5.7):** [ ] IT: vencida → `RELEASED`, estoque de volta e `InventoryReservationExpired` válido; não vencida e `CONFIRMED` intactas; duas chamadas concorrentes de `runOnce` → total liberado = 1, sem `40P01`; respeita `batchSize`. [ ] Implementar com `SKIP LOCKED` e `UPDATE sectors` em ordem de `sector_id`.
- **Pronto quando:** testes verdes; com `RESERVATION_TTL_MS` curto por override, o `available` volta e o order cancela com `RESERVATION_EXPIRED` (verificado em T12).
- **Valida:** IT; verificação ao vivo em T12. **Mapeia:** invariante TTL (RFC 0003 decisão 4), F1.

### T09: Order, API (`POST /orders`, `GET /orders/{id}`, `GET /orders`)

- **Owner:** Matheus. **Depende de:** T03 (`UserScopedTransaction`).
- **Files:** `modules/orders/{web,application,domain,infrastructure}/**` (RFC 0005 §5.1–5.3), `CursorCodec`, `OrderResponse`; `ORDER_MAX_QUANTITY` ≤ 6 e `ORDER_CREATED_VERSION` (1|2) no `AppConfig`.
- **Passos:**
  - [ ] **IT:** `POST` → 202 + `Location` + `{orderId,status:"PENDING"}`; mesma chave → 200 e o mesmo pedido; **duas requisições concorrentes com a mesma chave → um pedido só**; `Idempotency-Key` ausente/inválida, `quantity` 0 ou 7, campo desconhecido → 400; sem `X-User-Id` → 401; **BOLA:** usuário B em `GET /orders/{idDoA}` → 404; `GET /orders` pagina por cursor.
  - [ ] **IT contrato:** o `OrderCreated` no outbox valida contra `examples/OrderCreated.v1` e `v2`.
  - [ ] **F6 (parcial):** com o broker pausado, `POST /orders` responde 202 e o outbox acumula.
  - [ ] Implementar.
- **Pronto quando:** testes verdes; pelo gateway, `POST /orders` → 202 + `Location`.
- **Valida:** passos 4 e 9 do `scripts/happy-path.sh` (até o pedido aparecer `PENDING`); `scripts/f6-broker-down.sh` fecha só com T11.
- **Mapeia:** **R3**, BOLA, R6 (aceita com broker fora), bônus versionamento.

### T10: Order, saga (reserva, pagamento, compensações simples)

- **Owner:** Matheus. **Depende de:** T02, T09 (e T06 para o ponta a ponta).
- **Files:** `modules/orders/application/SagaHandlers.java` (um `MessageHandler` por evento: `InventoryReserved`, `InventoryRejected`, `PaymentApproved`, `PaymentFailed`), `domain/Transitions.java` (constantes SQL de RFC 0005 §5.4).
- **Passos:**
  - [ ] **IT por handler**, contra SQL real: cada guarda (estado errado → `STALE`); duplicata e fora de ordem não regridem o estado; `InventoryReserved` grava preço/moeda do evento e `payment_deadline_at = now() + ORDER_PAYMENT_TIMEOUT_MS`, e enfileira `PaymentRequested`; `PaymentApproved` dentro do prazo → `CONFIRMED` + `OrderConfirmed`; `PaymentFailed` → `CANCELLED(PAYMENT_FAILED)` + `OrderCancelled`; `InventoryRejected` → `CANCELLED(OUT_OF_STOCK)` (D1).
  - [ ] **Contrato:** payloads produzidos validam contra `contracts/examples`.
  - [ ] Implementar; registrar os handlers no consumidor.
- **Pronto quando:** testes verdes; **`scripts/happy-path.sh` exit 0** na stack limpa (register → login → catálogo → pedido → `CONFIRMED` → ingressos no user → comprovante no S3 → idempotência).
- **Valida:** `scripts/happy-path.sh`, `scripts/f1-compensation.sh`, `scripts/f5-race.sh`, `scripts/check-replicas.sh 3`.
- **Mapeia:** R1, R2 (ponta a ponta), R3, R4, F1, F5.

### T11: Order, deadline scanner, expiração, estorno tardio e `PaymentRefunded`

- **Owner:** Matheus. **Depende de:** T10, T03.
- **Files:** `modules/orders/application/DeadlineScanner.java` (`PeriodicJob`), handlers `InventoryReservationExpired` e `PaymentRefunded`, estorno em `SagaHandlers`.
- **Passos (RFC 0005 §5.5–5.6):**
  - [ ] **IT:** scanner cancela só `AWAITING_PAYMENT` vencido (`PAYMENT_TIMEOUT` + `OrderCancelled`); **corrida scanner × `PaymentApproved`** com duas threads (nunca `CONFIRMED` e `CANCELLED` ao mesmo tempo); aprovação tardia com pedido `CANCELLED` → `RefundRequested` + `refund_status = REQUESTED`; **aprovação ×3 → um `RefundRequested`**; aprovação tardia ainda `AWAITING_PAYMENT` vencido → `OrderCancelled` e `RefundRequested` na mesma TX; `PaymentRefunded` → `REFUNDED`; `InventoryReservationExpired` cancela de `PENDING`/`AWAITING_PAYMENT`.
  - [ ] Decidir a Decisão 5 do RFC 0005 (`correlation_id`) e registrá-la na RFC (linha nova, citando esta tarefa).
  - [ ] Implementar.
- **Pronto quando:** testes verdes; `f7-late-response.sh` e `f6-broker-down.sh` exit 0.
- **Valida:** `scripts/f7-late-response.sh`, `scripts/f6-broker-down.sh`, `scripts/f8-scale.sh`.
- **Mapeia:** F6, F7, F8, R6, R5 (consumo do resultado), D10.

### T12: Verificação ponta a ponta e ensaio

- **Owner:** Pedro e Matheus (Felipe revisa o inventory). **Depende de:** T07, T11 (T08 se existir).
- **Passos:**
  - [ ] Stack limpa: `docker compose down -v && docker compose up -d --build && scripts/wait-healthy.sh 300 && scripts/verify-infra.sh | tail -1`.
  - [ ] Rodar em sequência `scripts/happy-path.sh`, `check-replicas.sh 3`, `f1` … `f8`, `demo-versioning.sh`, `verify-platform.sh`, `check-ports.sh`, `check-autonomy.sh` dos dois serviços; todos exit 0.
  - [ ] Medir memória e tempo de build/subida (Risco 5 e 6) e ajustar `compose.yaml`/`JAVA_TOOL_OPTIONS` se preciso.
  - [ ] Verificação ao vivo da expiração (T08): `RESERVATION_TTL_MS` curto → `available` volta.
  - [ ] Atualizar READMEs, `docs/runbook.md` (comandos, tempos), `docs/roadmap.md` (donos, D7) e o status das RFCs 0004–0006 (✅ onde entregue).
  - [ ] Pedir o aceite ou a substituição do ADR-0022 ao grupo.
- **Pronto quando:** roteiro completo sem intervenção manual, no tempo da apresentação; relatório das falhas (se houver) no PR.
- **Valida:** o conjunto acima. **Mapeia:** todos os R/F.

### T13 (Should): Versionamento v1/v2/v3

- **Owner:** Felipe. **Depende de:** T06, T09.
- **Passos:** [ ] `scripts/demo-versioning.sh`: v1 reservado com `channel = API`, v2 com `WEB`, v3 na DLQ sem efeito. [ ] `ORDER_CREATED_VERSION=1 docker compose up -d order-service && scripts/happy-path.sh`, depois voltar a v2. [ ] Atualizar `docs/demo/versioning.md` com os caminhos Java.
- **Pronto quando:** os dois comandos com exit 0. **Valida:** `scripts/demo-versioning.sh`. **Mapeia:** bônus versionamento.

### T14 (Could): OpenTelemetry nos serviços Java

- **Owner:** Felipe e Matheus. **Depende de:** T07, T11 (só depois da linha de corte).
- **Passos (RFC 0004 §5.9):** [ ] Agente OTel Java no `Dockerfile` (versão e sha256 fixados) + `tracing/Spans` (span `consumer` com link e span `producer` no relay, `traceparent` real no outbox). [ ] Teste: `OutboxWriter` grava `traceparent` quando há span ativo; `SqsConsumer` continua o trace do produtor. [ ] Endpoint OTLP com esquema (D3).
- **Pronto quando:** no Jaeger, um trace único cobre `POST /orders` → `TicketsIssued` nos serviços Go, Java e C#. Sem `OTEL_EXPORTER_OTLP_ENDPOINT`, nada falha.
- **Valida:** Jaeger (`http://localhost:16686`) após `happy-path.sh`. **Mapeia:** bônus OTel (ADR-0012).
