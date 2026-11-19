# Serviços restantes: visão geral, ordem de entrega e divergências

> **Este documento só descreve e costura planos: nenhum código, migration, teste ou script foi escrito ao produzi-lo.** Complementa o [plano de order e inventory em Java](2026-11-19-java-order-inventory.md) (PR #13, RFCs 0004–0006, ADR-0022 proposto). Hoje: qui 19/11. Freeze: ter 24/11 às 12h. Apresentação: qua 25/11.

## 1. Resumo para quem tem 2 minutos

- **Quase tudo já existe.** gateway, notification e autoscaler estão completos; payment tem consumidor, worker com lease, provedor simulado e estorno; user tem perfil e read model; auth tem register, login e JWKS. O que falta é pequeno e concentrado: **auth** (refresh, logout, `TokenRevoked`, compensação) e **user** (publicar `UserCreationFailed`).
- **O caminho crítico não está aqui.** order e inventory são só scaffold (ADR-0021): `happy-path.sh` e F1, F3–F8 **falham hoje**. As verificações finais de payment (F1/F7/F8) e notification (comprovante real) esperam o PR #13.
- **Um bug de integração:** o gateway não marca `POST /auth/refresh` como pública (RFC 0002 §5.5 manda), então o refresh nunca funcionaria. Correção de uma linha: **G01**, primeira tarefa a fazer.
- **Duas decisões abertas para o grupo**, sem ADR ainda (ADR aceito não se edita, e ainda não há decisão): onde o auth guarda o estado de revogação (D1) e qual sinal o autoscaler usa (D2).

## 2. Documentos produzidos

| Serviço | RFC | Plano |
|---|---|---|
| auth-service (Java) | [0007](../../rfc/0007-design-do-auth-service-em-java.md) | [auth](2026-11-19-auth-service.md) (A01–A08) |
| user-service (C#) | [0008](../../rfc/0008-design-do-user-service-em-csharp.md) | [user](2026-11-19-user-service.md) (U01–U05) |
| payment-service (C#) | [0009](../../rfc/0009-design-do-payment-service-em-csharp.md) | [payment](2026-11-19-payment-service.md) (P01–P06) |
| gateway (Go) | [0010](../../rfc/0010-design-do-gateway-em-go.md) | [gateway](2026-11-19-gateway.md) (G01–G05) |
| notification-service (Go) | [0011](../../rfc/0011-design-do-notification-service-em-go.md) | [notification](2026-11-19-notification-service.md) (N01–N03) |
| autoscaler (Go) | [0012](../../rfc/0012-design-do-autoscaler-em-go.md) | [autoscaler](2026-11-19-autoscaler.md) (S01–S04) |

**ADR novo: nenhum.** Não surgiu decisão arquitetural *tomada*; surgiram duas *pendentes* (D1 e D2). Se o grupo aceitar a opção Postgres em D1, abre-se o ADR-0023 (estado de revogação do auth no Postgres, substituindo as menções do ADR-0014); se mudar o sinal em D2, outro ADR que substitua o 0015. O ADR-0022 é do PR #13.

## 3. Dependências entre serviços

```
              ┌────────────── order Java (T09–T11, PR #13) ──────────────┐
              │   consome PaymentApproved/Failed/Refunded                │
  payment ────┤   publica PaymentRequested / RefundRequested             │
  (P04)       └──────────────────────────────────────────────────────────┤
  inventory Java (T05–T08) ── TicketsIssued ──► user (read model)        │ happy-path.sh,
                                         └────► notification (S3) (N03)  │ f1..f8
  gateway G01 ──► auth A02 (refresh) ── A03 (logout/TokenRevoked) ──► gateway G04
  auth A04/A05 ◄── user U03 (UserCreationFailed)  ; runner de consumidor: T02 (PR #13)
  autoscaler S03 ── lê filas do payment (e, opcional, inventory)
```

| Quem espera | Espera por | Efeito se atrasar |
|---|---|---|
| A02/A06 (auth) | G01 (gateway) | refresh inutilizável |
| A04/A05 (auth) | runner de T02 (PR #13) | compensação só por injeção de evento |
| U03/U04 | decisão U02 e A05 | compensação só por injeção |
| P04, N03 (happy path real) | T07, T10, T11 (PR #13) | F1/F7/F8 e comprovante real sem prova |
| S03 | nada crítico | bônus cortável |

## 4. Ordem de entrega

| Quando | Entrega | Quem |
|---|---|---|
| qui 19/11 | **G01**; A01, U01 (README), U02 (decisão), P01, S01 (docs) | Pedro, Felipe, Yasmin, Gabriel |
| sex 20/11 | A02, G02/G03, P02/P03, U01 (BOLA), N01 | idem |
| sáb 21/11 | A03 + A06 (`auth-check.sh`), U03, P04 (F1), N02/N03 | idem; happy path do order verde (PR #13) |
| dom 22/11 até 12h (**linha de corte**) | A04/A05, G04, P04 (F7/F8), U04/A07 | idem |
| dom 22/11 tarde – seg 23/11 | A07/A08, P05/P06, S02–S04 (bônus), ensaio | todos |
| ter 24/11 até 12h | **freeze**: só bloqueantes | todos |

**Cortes em ordem** (alinhados ao roadmap): S04 → S03/S02 (autoescala) → A08/U05/P06 → A05/A07/U03/U04 (compensação do cadastro fica por injeção de evento, declarado) → P05. **Nunca cortar:** G01, A02, A03, A06, P04, N03, U01.

## 5. Matriz requisito → tarefa

| Requisito / cenário | Tarefas deste conjunto | Tarefas do PR #13 | Script |
|---|---|---|---|
| ≥4 serviços, ≥3 linguagens, banco por serviço | (já entregue) | — | `docker compose ps`, `check-autonomy.sh` |
| Contratos com envelope | A03, U03 (validam contra `contracts/examples`) | — | `make -C contracts check` |
| Gateway com JWT local e revogação | G01, G04, A02, A03 | — | `gateway-check.sh`, `auth-check.sh` |
| Saga com compensação (compra) | P04 | T07, T10, T11 | `f1-compensation.sh` |
| Saga curta (cadastro) | A04, A05, A07, U02–U04 | — | `register-compensation.sh` |
| Dependência instável (R5) | P01–P04 | T10/T11 | `f1`, `f7` |
| Evento com 2+ consumidores | N03, U01 | T07 | `happy-path.sh` |
| 3 categorias de nuvem (S3, Secrets Manager) | N02, N03; auth (já) | — | `verify-infra.sh`, `happy-path.sh` |
| **R1** idempotência | A05, U03, N02 | T02, T06 | `f3-duplicate.sh` |
| **R2** venda dupla | — | T06, T10 | `f5-race.sh` |
| **R3** API assíncrona | — | T09 | `happy-path.sh` |
| **R4** consumidores concorrentes | P04 | T02, T10 | `check-replicas.sh 3` |
| **R5** | P01–P04 | T10/T11 | `f1`, `f7` |
| **R6** reconexão | A03 (logout com broker fora) | T02, T09, T11 | `f6-broker-down.sh` |
| **F1** | P04 | T10, T07 | `f1-compensation.sh` |
| **F7** | P03, P04 | T11 | `f7-late-response.sh` |
| **F8** | P04, S03 | T10–T12 | `f8-scale.sh`, `demo-autoscale.sh` |
| F2, F3, F4, F5, F6 | (testes de plataforma por serviço, sem tarefa nova aqui) | T02, T06, T09, T11 | `f2..f6` |
| BOLA | U01 | T09 | teste xUnit / testes do order |
| Bônus CQRS | U01 | — | `happy-path.sh` |
| Bônus OTel | (Go e C# já entregues) | T14 (Java; **o auth também precisa**, ver D13) | Jaeger |
| Bônus autoescala | S02–S04 | — | `demo-autoscale.sh` |

## 6. Divergências e contradições encontradas (registradas, não decididas)

Quem decide é o grupo ou o dono do artefato. "Tratamento" diz o que os planos assumiram até lá.

| ID | O que diverge | Impacto | Tratamento nos planos |
|---|---|---|---|
| **D1** | **Estado de revogação do auth.** ADR-0014 e arquitetura §2/§8 dizem "Redis do auth" e tabela `revoked_tokens`; RFC 0002 §5.7 diz `issued_access_tokens`; o README do auth repete Redis e `revoked_tokens`; as migrations e o `compose.yaml` não têm nenhum dos três (só `auth-db`). | Logout e compensação não têm onde guardar `jti`. | RFC 0007 Decisão 2 (`OPEN`) propõe Postgres + `V0006`. Aceite pendente; vira ADR-0023 se aprovado |
| **D2** | **Sinal da autoescala.** ADR-0015 escala pela fila SQS, mas o consumidor do payment só grava `PENDING` e dá ack: a fila fica vazia e o backlog está em `payments`. A demo acumula pausando as réplicas. | Demo prova o mecanismo, não o caso de uso. | RFC 0012 §5.2 (4 opções); S02 decide |
| **D3** | **`POST /auth/refresh` não é pública no gateway** (`cmd/gateway/main.go:85`), embora o RFC 0002 §5.5 a liste. | Refresh devolve 401 com access expirado. | G01 (uma linha + teste) |
| **D4** | **`UserCreationFailed` nunca é produzido** e nenhum cadastro real o dispara (auth valida mais que o user-service). Também não existe cenário numerado nem script (`F1–F8` não o cobrem; roadmap F3 o lista). | A saga curta do enunciado não é demonstrável de ponta a ponta. | RFC 0008 Decisão 2 (`OPEN`); `register-compensation.sh` com fallback por injeção |
| **D5** | **Consumidor do auth não está ligado:** `SqsConsumer` existe na plataforma Java, mas nenhum bean o instancia no auth (README e arquitetura §3 dizem que consome `UserCreationFailed`). | Compensação impossível hoje. | A04 reaproveita o runner de T02 (PR #13) |
| **D6** | **order e inventory são scaffold** (ADR-0021): roadmap F2/F3 e READMEs dos outros serviços tratam o caminho feliz e F1–F8 como entregues. `happy-path.sh` e F1, F3–F8 falham. | Verificação final dos Go/C# depende do PR #13. | Planos marcam P04/N03 como dependentes de T07/T10/T11 |
| **D7** | **READMEs defasados:** payment (estorno "na F3", tabela de env sem `PAYMENT_*`), user (`OrderConfirmed` como fonte do read model, F1/F2 pendentes), notification ("Por onde começar" F1/F2), auth (Redis, `revoked_tokens`, rotas "F2"). | Quem lê subestima o estado. | A01, U01, P01, N01 |
| **D8** | **Links quebrados:** gateway e notification citam `adr/0012-opentelemetry-collector-jaeger.md`; autoscaler cita `adr/0015-queue-depth-autoscaling-docker-api.md`. Os arquivos reais são `0012-opentelemetry-tracing.md` e `0015-queue-based-autoscaler.md`. | Links 404. | G03, S01, N01 |
| **D9** | **Lease = timeout no F7.** RFC 0003 §5.1 exige `PAYMENT_WORKER_LEASE_MS > PAYMENT_PROVIDER_TIMEOUT_MS`; o F7 sobe o timeout para 30000 e o lease padrão é 30000; `AppConfig` não valida. | Janela de dupla reivindicação (a cobrança continua única pela idempotência do provedor). | P02, P03 |
| **D10** | **`correlationId` do worker do payment é novo** (`Guid.NewGuid()`), não o do pedido; a arquitetura §10 promete `correlationId` em todos os logs/eventos da saga. | Trace da saga fica solto no payment. | P05 (`OPEN`, migration opcional) |
| **D11** | **Status e datas defasados:** RFCs 0001–0003 ainda "Draft"/"Sem código" (RFC 0002 §3 diz "Sem código"); `INDEX.md` na `main` diz próximo número livre 0004; o roadmap diz "Hoje: qui 12/11". | Leitura enganosa. | Não editei prosa de RFC; atualização de status na T12 do PR #13 e no fim deste conjunto |
| **D12** | **Dono duplo:** Felipe é dono do auth e do inventory (ADR-0021; PR #13 T05–T08, T13); Pedro é dono de gateway, notification, autoscaler e revisão; roadmap ainda lista Pedro no inventory (D7 do PR #13). | Gargalo de agenda no fim de semana. | Faixas Must/Should/Could; auth A04/A05 cortáveis |
| **D13** | **OTel em Java:** o auth não exporta traces (T14 do PR #13 cobre só order e inventory). A promessa "um trace cobre `POST /orders` até `TicketsIssued`" não passa pelo auth, então não depende dele; mas o cadastro (saga curta) não terá trace. | Bônus parcial no cadastro. | Fora dos planos; sugerir incluir o auth na T14 |
| **D14** | **Retenção:** ADR-0005/0006 prometem `OUTBOX_RETENTION_H` e `PROCESSED_MESSAGES_RETENTION_D`; nenhum serviço implementa (mesma D6 do PR #13). | Tabelas crescem. | Registrado nos RFCs (Out of scope); não é tarefa |
| **D15** | **`user-service` assina `OrderConfirmed` sem efeito** (arquitetura §3/§7 e Terraform dizem que consome; o read model sai de `TicketsIssued`). | Mensagens consumidas à toa. | RFC 0008 Decisão 5: manter (mexer exige Terraform) |
| **D16** | **Tamanho de `name`:** auth aceita 1–100, `UserRegistered` aceita 1–120. | Nenhum (mais estrito é seguro). | Nenhum |
| **D17** | **Numeração:** o PR #13 usa RFCs 0004–0006 e ADR-0022; este conjunto usa RFCs 0007–0012 e não cria ADR. `docs/rfc/INDEX.md` e `docs/adr/README.md` serão alterados nos dois branches. | Conflito de merge nas tabelas e no "próximo número livre". | Resolver no merge: manter as linhas dos dois e deixar **0013** como próximo RFC livre |

## 7. Riscos transversais

1. **Caminho crítico externo:** tudo que prova F1–F8 passa pelo PR #13. Sem order/inventory, os planos deste conjunto só provam unidade e integração local.
2. **Agenda de Felipe e Pedro** (D12). Mitigação: cortes da §4.
3. **Memória da máquina da demo** (~20 containers + 3 JVMs ×2 + autoscaler com até 5 clones). Medir no ensaio (risco 5 do PR #13).
4. **Scripts novos** (`auth-check.sh`, `register-compensation.sh`, `notification-check.sh`) precisam de dono e de entrada no `docs/runbook.md`; sem isso o ensaio não os executa.

## 8. Definition of Done do conjunto

- `scripts/auth-check.sh`, `gateway-check.sh`, `register-compensation.sh` (ou a injeção declarada), `notification-check.sh`, `demo-autoscale.sh` com exit 0 numa stack limpa.
- `make lint typecheck test` (e integração) verdes nos seis serviços tocados; `scripts/check-autonomy.sh services/<svc>` verde.
- READMEs conferem com os RFCs 0007–0012; D7 e D8 zerados.
- Decisões D1 e D2 registradas (com ADR, se mudaram o que está aceito).
- Status dos RFCs 0007–0012 atualizado no `INDEX.md` por quem entregou (✅ quando completo), com linha nova nas tabelas de decisão quando a execução mudar algo, sem reescrever a prosa.
