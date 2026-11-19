# Roadmap — Ticket Pro

**Hoje:** qui 12/11/2026. **Apresentação:** qua 25/11 (13 dias). **Código público:** qua 02/12.

Princípio: primeiro o **núcleo avaliado** (R1–R6, F1–F8 e os 3 elementos obrigatórios), depois os bônus. A falta de um bônus nunca pode derrubar a nota base. Cada fase termina num **checkpoint verificável**: comando, script ou teste verde, nunca "achamos que está pronto".

## Visão geral

| Fase | Datas | Entrega | Checkpoint |
|---|---|---|---|
| **F0** Fundação | qui 12 – sex 13/11 | Docs aprovados, spikes no MiniStack, esqueleto do repo e infra | `docker compose up` sobe MiniStack + infra-init (Terraform) + Jaeger; spikes respondidos |
| **F1** Plataforma | sáb 14 – seg 16/11 | Libs `shared/go`, `shared/dotnet`, equivalente Java, sync, esqueleto dos 8 serviços | Todos os serviços `healthy`; teste de R1 e R6 verde nas 3 linguagens |
| **F2** Caminho feliz | ter 17 – qui 19/11 | Cadastro, login, compra ponta a ponta, ingressos, comprovante S3 | Script `happy-path.sh` verde; R2, R3, R4, R5 verdes |
| **F3** Falhas | sex 20 – sáb 21/11 | Compensações, deadline, estorno, expiração, DLQ, revogação, scripts F1–F8 | `scripts/f1..f8` todos com exit 0 |
| **F4** Bônus | dom 22 – seg 23/11 | OTel completo, `OrderCreated` v2, autoescala | Trace da saga no Jaeger; demo v1/v2/v3; autoscaler escala o payment |
| **F5** Congelamento | ter 24/11 | README, runbook, ensaio cronometrado | Ensaio completo ≤ tempo da apresentação, sem intervenção manual |
| — | **qua 25/11** | **Apresentação** | — |
| **F6** Publicação | qui 26/11 – qua 02/12 | Ajustes pós-banca, limpeza, repo público | Repo público, tag `v1.0.0`, CI verde |

## F0: Fundação (12–13/11)

| Tarefa | Dono | Pronto quando |
|---|---|---|
| Aprovar e commitar a etapa de docs (branch `docs/architecture-foundation` → PR) | Grupo | PR aprovado e mergeado |
| **Spike MiniStack:** raw delivery, filter policy, redrive para DLQ, `ApproximateReceiveCount`, `ApproximateNumberOfMessages`, `ChangeMessageVisibility`, `docker pause` preservando filas | Pedro | Respostas registradas. Cada plano B acionado vira ADR |
| **Spike Terraform:** provider AWS contra o MiniStack (SNS, SQS, assinaturas, S3, Secrets Manager, `tls_private_key`) | Matheus | `terraform apply` limpo dentro do `infra-init` |
| `docker-compose.yml` da raiz com `include:`, `infra/compose/compose.yaml` (ministack, infra-init, otel-collector, jaeger), redes `edge`/`backend`/`<svc>-data` | Matheus | `docker compose up` sobe a infra saudável |
| Templates de serviço por linguagem: `Makefile`, `Dockerfile`, `compose.yaml`, `.env.example`, `README.md` | Pedro (Go), Felipe e Matheus (Java), Gabriel (C#) | Serviço "hello" com `/health/live` buildando a partir da própria pasta |
| CI: `root.yml` (contracts validate + `sync-shared --check`) e um workflow por serviço | Matheus | Pipeline verde no PR |

**Checkpoint F0:** infra sobe com um comando, os spikes estão respondidos e os templates buildam isolados.

## F1: Plataforma (14–16/11)

| Tarefa | Dono | Requisito |
|---|---|---|
| `shared/go`: envelope, messaging (SNS/SQS, desembrulho), outbox + relay, idempotency, retry, logging, OTel propagation | Pedro | R1, R6 |
| `shared/dotnet`: idem | Gabriel + Yasmin | R1, R6 |
| `platform/` no auth, no order e no inventory (Java), com o mesmo contrato | Felipe, Matheus | R1, R6 |
| `scripts/sync-shared.sh` (+ `--check`, reescrita de import Go) | Pedro | ADR-0017 |
| Esqueleto dos 8 serviços com migrations Flyway (`outbox`, `processed_messages`, roles `owner/app/worker`), health live/ready e config validada | cada dono | padrões §6, §9 |
| Testes de contrato lendo `contracts/` vendorizado | cada dono | RFC 0001 |

**Checkpoint F1:**
- `docker compose up` → os 8 serviços `healthy`.
- Teste de integração por linguagem: mensagem duplicada 5× gera 1 efeito (R1); relay com broker parado acumula e drena ao voltar (R6).

## F2: Caminho feliz (17–19/11)

| Tarefa | Dono | Requisito |
|---|---|---|
| auth: register (+ outbox `UserRegistered`), login, JWKS, chave do Secrets Manager | Felipe | RFC 0002 |
| gateway: roteamento, JWT via JWKS, remoção/injeção de headers, correlation id | Pedro | ADR-0002 |
| user: perfil por `UserRegistered`, `GET /me`, read model `GET /me/tickets` (CQRS) | Yasmin | bônus CQRS |
| order: `POST /orders` (202 + `Idempotency-Key`), `GET /orders/{id}`, orquestração da saga | Matheus | R3 |
| inventory: catálogo seed, `UPDATE` condicional, reservas, `TicketsIssued` | Pedro | R2 |
| payment: consumidor + worker com lease, provedor simulado configurável | Gabriel | R5 |
| notification: consome `TicketsIssued`/`OrderCancelled`, comprovante no S3 | Pedro + Matheus | 2+ consumidores |
| Réplicas ×2 (order, inventory, payment) no compose | Matheus | R4 |
| `scripts/happy-path.sh`: register → login → compra → poll status → ingressos → objeto no S3 | Matheus | — |

**Checkpoint F2:**
- `happy-path.sh` com exit 0.
- Teste de R2 (100 goroutines × 10 unidades) verde.
- Logs mostram duas réplicas consumindo a mesma fila (R4).

## F3: Falhas (20–21/11)

| Tarefa | Dono | Cenário |
|---|---|---|
| order: `PaymentFailed` → `OrderCancelled`; deadline scanner; `InventoryReservationExpired`; estorno tardio | Matheus | F1, F7 |
| inventory: liberação idempotente, job de expiração, validação de TTL × deadline no boot | Pedro | F1 |
| payment: `RefundRequested` → `PaymentRefunded`; timeout duro | Gabriel | F7 |
| Consumidores: classificação venenosa/transitória, visibility backoff | cada dono | F4 |
| auth/user: `UserCreationFailed` → compensação do cadastro; logout → `TokenRevoked` → Redis do gateway | Felipe, Yasmin, Pedro | — |
| gateway: rate limit por IP/usuário | Pedro | RFC 0002 |
| Scripts `scripts/f1-compensation.sh` … `f8-scale.sh` e `scripts/dlq.sh`, todos com verificação automática | Matheus (+ dono de cada serviço) | F1–F8 |
| Teste de BOLA (usuário B → 404) | Matheus, Yasmin | RFC 0002 |

**Checkpoint F3:** os 8 scripts F1–F8 com exit 0 em sequência, a partir de um `docker compose up` limpo.

## F4: Bônus (22–23/11), em ordem de prioridade

| # | Bônus | Situação ao chegar aqui | Tarefa restante | Dono |
|---|---|---|---|---|
| 1 | CQRS | Entregue na F2 | Nenhuma | Yasmin |
| 2 | IaC | Entregue na F0 | Nenhuma | Matheus |
| 3 | OpenTelemetry | Propagação na F1 | Spans de consumer/producer linkados, auto-instrumentação de HTTP/DB, verificar trace completo no Jaeger | todos |
| 4 | Versionamento | Schemas v1/v2 prontos | Upcaster v1→v2 no inventory, `ORDER_CREATED_VERSION`, roteiro v1/v2/v3→DLQ | Pedro, Matheus |
| 5 | Autoescala | — | `services/autoscaler` (ADR-0015) + demo com fila acumulada | Pedro |

**Linha de corte:** se o checkpoint F3 não estiver verde até **dom 22/11 às 12h**, a F4 começa por F3 pendente, e os bônus 5 e 4 (nessa ordem) são os primeiros cortados.

## F5: Congelamento (24/11)

- **Freeze de código às 12h.** Depois disso, só correção de bug bloqueante.
- `README.md` (subir, testar, demonstrar) e `docs/runbook.md` com roteiro F1–F8: comando, o que observar (log/Jaeger/query) e resultado esperado. Usar a skill `engineering:documentation`.
- Ensaio cronometrado em máquina limpa (`git clone` → `docker compose up` → roteiro), com cada integrante apresentando seu serviço.

## F6: Publicação (26/11 – 02/12)

- Correções apontadas na banca (novos ADRs se mudar decisão).
- Revisão de segredos (`git log` sem credenciais reais) e limpeza de branches.
- Tag `v1.0.0` e repositório **público em 02/12**.

## Checkpoints por requisito

| Requisito | Fase | Evidência |
|---|---|---|
| ≥4 serviços / ≥3 linguagens / banco por serviço | F1 | `docker compose ps`; redes `<svc>-data` |
| Mensageria + 3 categorias de nuvem | F0/F2 | Terraform aplicado; S3 com comprovante; segredo JWT lido |
| Contratos com envelope | ✅ docs | `contracts/` + `npm run validate` |
| Dockerfile + health check | F1 | Todos `healthy` |
| Saga com compensação | F3 | `f1-compensation.sh` |
| Dependência instável | F2 | Envs `PAYMENT_*` mudam o comportamento |
| Evento com 2+ consumidores | F2 | `TicketsIssued` → user + notification |
| R1 | F1 | Teste de duplicata nas 3 linguagens; `f3-duplicate.sh` |
| R2 | F2 | Teste de concorrência; `f5-race.sh` |
| R3 | F2 | 202 + `GET /orders/{id}` |
| R4 | F2 | 2 réplicas na mesma fila |
| R5 | F2 | `PAYMENT_LATENCY_MS`, `PAYMENT_FAILURE_RATE` |
| R6 | F1 | Teste do relay; `f6-broker-down.sh` |
| F1–F8 | F3 | `scripts/f1..f8` exit 0 |
| Bônus | F0–F4 | Tabela da F4 |

## Riscos de cronograma

| Risco | Mitigação |
|---|---|
| MiniStack sem suporte a algum recurso (redrive, atributos) | Spike no dia 1, plano B já descrito (RFC 0003 §9, ADR-0015) |
| Pedro sobrecarregado (3 componentes Go) | Matheus assume o notification e os scripts; o autoscaler é o primeiro corte |
| Integração tardia entre linguagens | Contratos fechados agora + testes de contrato desde a F1 |
| Máquina da demo sem memória para ~20 containers | Imagens slim, limites de memória no compose, ensaio em máquina-alvo na F5 |
