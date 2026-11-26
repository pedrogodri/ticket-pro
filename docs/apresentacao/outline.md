# Apresentação — Ticket Pro (outline slide a slide)

Apresentação: qua 25/11/2026. Fontes: `docs/arquitetura.md`, `docs/registro-tema.md`, `docs/adr/`, `docs/rfc/`, `docs/runbook.md`, `docs/roadmap.md`.
Tempo-alvo: confirmar com o professor. Abaixo, 26 slides (~1 min cada, demo ao vivo à parte). Marcados com ⚡ os cortáveis se o tempo for curto.

## Bloco 1: Abertura e tema (3 slides)

**1. Capa**
- Ticket Pro: backend distribuído de venda de ingressos
- Sistemas Distribuídos, FURB, 2026
- Integrantes: Pedro, Felipe, Gabriel, Yasmin, Matheus

**2. O problema / o tema**
- Venda de ingressos por setor e quantidade (estilo Ticketmaster), somente backend
- Por que é um bom tema distribuído: concorrência pelo mesmo estoque, pagamento lento e instável, falhas parciais
- Foco: corretude sob concorrência e falha, não throughput

**3. O que o enunciado exige (e onde atendemos)**
- 3 elementos obrigatórios: saga com compensação, dependência instável, evento com 2+ consumidores
- Requisitos R1–R6 (idempotência, zero venda dupla, API assíncrona, consumidores concorrentes, dependência por env, reconexão)
- Cenários de falha F1–F8
- Bônus: OpenTelemetry, IaC, autoescala, CQRS, versionamento de evento

## Bloco 2: Visão da solução (4 slides)

**4. Visão geral da arquitetura**
- Diagrama de componentes (`arquitetura.md` §2): cliente → gateway → serviços → SNS/SQS → bancos
- 8 serviços, 3 linguagens, um banco por serviço, ~25 containers
- Tudo sobe com `docker compose up`

**5. Serviços e responsabilidades**
- Tabela: gateway, auth, user, order, inventory, payment, notification, autoscaler
- Linguagem, dono de quê, o que publica/consome

**6. Por que 3 linguagens e quem faz o quê**
- Go: gateway, notification, autoscaler (Pedro)
- Java: auth, inventory (Felipe), order (Matheus)
- C#: payment (Gabriel), user (Yasmin)
- ADR-0008 e ADR-0021; contrato único (`contracts/`) mantém as linguagens intercambiáveis

**7. Ponto de entrada único e segurança**
- Só o gateway publica porta (`:8080`)
- JWT validado localmente via JWKS (ADR-0002); gateway injeta `X-User-Id`
- Revogação de token por evento (`TokenRevoked`, ADR-0014); rate limit; proteção contra BOLA (RFC 0002)
- Chave JWT no Secrets Manager

## Bloco 3: Comunicação e dados (4 slides)

**8. Mensageria: SNS + SQS**
- Tópico por domínio, uma fila por consumidor, filter policy por `type`, DLQ com `maxReceiveCount=3` (ADR-0001)
- Envelope padrão: `messageId, correlationId, type, version, occurredAt, payload` (RFC 0001)
- Contratos em JSON Schema + AsyncAPI

**9. Mapa de eventos**
- Tabela de 15 eventos: quem publica, quem consome
- Destaque: `TicketsIssued`, `OrderConfirmed`, `OrderCancelled` com 2+ consumidores

**10. Banco por serviço e isolamento**
- Postgres por serviço, redes `<svc>-data` isoladas, nenhum serviço resolve o host do banco alheio (ADR-0004, ADR-0020)
- Roles `owner/app/worker` (RLS no app, BYPASSRLS só no worker)
- Dados alheios só chegam por evento
- ⚡ CQRS: "meus ingressos" no user-service

**11. Nuvem emulada e IaC**
- MiniStack em vez de nuvem pública (ADR-0009)
- 3 categorias: SNS/SQS, S3 (comprovantes), Secrets Manager (ADR-0010)
- Terraform aplicado pelo `infra-init` (ADR-0011, ADR-0019)

## Bloco 4: Saga e padrões de confiabilidade (6 slides)

**12. A saga de compra (diagrama de sequência)**
- `POST /orders` → 202 → reserva → pagamento → confirmação → emissão de ingressos
- Orquestrada pelo order-service (ADR-0003)

**13. Máquina de estados do pedido**
- `PENDING → AWAITING_PAYMENT → CONFIRMED | CANCELLED`
- Transição por `UPDATE ... WHERE status = <esperado>`: evento duplicado ou fora de ordem não regride

**14. Compensação e resposta tardia**
- Falha ou deadline → `OrderCancelled` → inventory devolve estoque
- Aprovação tardia (F7) → `RefundRequested` → `PaymentRefunded`
- Invariante de TTL da reserva × deadline, validada no boot
- ⚡ Saga curta de cadastro (`UserCreationFailed`)

**15. Zero venda dupla (R2)**
- `UPDATE sectors SET available = available - q WHERE available >= q` + `CHECK (available >= 0)` (ADR-0007)
- Resultado do F5: 100 pedidos, 10 unidades → exatamente 10 reservados, 90 `OUT_OF_STOCK`

**16. Transactional outbox + consumidor idempotente (R1, R6)**
- Publica só via outbox, na mesma TX do efeito; relay com backoff (ADR-0005)
- Consome: valida → TX → `processed_messages` → efeito → commit → só então ack (ADR-0006)
- At-least-once + idempotência = efeito exatamente uma vez

**17. Dependência instável (R5) e tratamento de falhas**
- Provedor de pagamento simulado: `PAYMENT_LATENCY_MS`, `PAYMENT_FAILURE_RATE`, timeout
- Consumidor grava `PENDING` e dá ack; worker com `SKIP LOCKED` + lease chama o provedor fora de transação
- Retry, mensagem venenosa → DLQ, visibility backoff (RFC 0003)

## Bloco 5: Escala, observabilidade e métodos (4 slides)

**18. Escalabilidade (R4, F8)**
- 2 réplicas de order/inventory/payment na mesma fila; `SKIP LOCKED` no relay e nos jobs
- Autoscaler por profundidade de fila via Docker API (ADR-0015)

**19. Observabilidade**
- Logs JSON com `correlationId`, `traceId`
- OpenTelemetry nos 3 runtimes, Collector + Jaeger; um trace de `POST /orders` até `TicketsIssued` (ADR-0012)
- Health `live` e `ready`

**20. Como trabalhamos: método**
- Docs primeiro: ADRs (22), RFCs (12), contratos como fonte da verdade
- Monorepo com pastas de serviço autônomas e libs vendorizadas + `sync-shared --check` (ADR-0016, 0017)
- Roadmap em fases com checkpoint verificável; branch por tarefa, Conventional Commits, CI por serviço
- Testes: unitário, contrato, integração (Testcontainers)

**21. Desafios e decisões que mudaram**
- Spikes no MiniStack (raw delivery, redrive, atributos de fila) e planos B
- Plataforma Java copiada do auth com checagem de deriva (ADR-0022)
- Troca de linguagem de order/inventory (ADR-0021)

## Bloco 6: Demonstração e fechamento (5 slides)

**22. Roteiro da demo**
- Caminho feliz: `scripts/happy-path.sh` (cadastro → login → pedido → CONFIRMED → ingressos → comprovante no S3)
- Ordem dos cenários F1–F8 e o que observar em cada um (`docs/runbook.md`)

**23. Cenários de falha F1–F8 (tabela)**
- F1 compensação, F2 kill de consumidor, F3 duplicata ×5, F4 mensagem venenosa
- F5 corrida 100 req/10 un., F6 broker fora 30 s, F7 resposta tardia, F8 escala para 3 réplicas

**24. Bônus demonstrados**
- Trace no Jaeger, `OrderCreated` v1/v2/v3→DLQ, autoescala, CQRS, IaC

**25. Resultados e estado atual**
- Matriz requisito → evidência (`arquitetura.md` §11)
- Estado honesto: serviços Go entregues; Java e C# em implementação pelos donos *(ajustar com o status real até 25/11)*

**26. Conclusão, próximos passos e perguntas**
- Aprendizados: consistência eventual, idempotência, compensação
- Evoluções (`arquitetura.md` §12): CDC no outbox, assentos numerados, fila de espera, KEDA
- Repo público em 02/12; agradecimentos e perguntas

---

## Pontos em aberto (preciso de vocês)
- Duração da apresentação e se haverá demo ao vivo ou gravada
- Estado real de Java/C# no dia 25 (muda os slides 21, 24 e 25)
- Quem apresenta cada bloco (sugestão: cada um fala do seu serviço nos slides 5–6)
- Formato final do deck (Google Slides, PowerPoint, Canva, HTML)
