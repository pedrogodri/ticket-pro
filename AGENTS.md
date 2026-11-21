# Agent Instructions — Ticket Pro

Sistema distribuído acadêmico (FURB). Só backend. Respostas em PT-BR; código, identificadores, commits e branches em inglês.

## Stack
| Serviço | Linguagem | Pasta |
|---|---|---|
| gateway, notification, autoscaler | Go | `services/{gateway,notification-service,autoscaler}` |
| auth, order, inventory | Java 21 (Spring Boot), **sem código** | `services/{auth-service,order-service,inventory-service}` |
| payment | C# (.NET 10) | `services/payment-service` |
| user | C# (.NET 10), **sem código** | `services/user-service` |

Infra: MiniStack (`AWS_ENDPOINT_URL=http://ministack:4566`), SNS→SQS+DLQ, S3, Secrets Manager, PostgreSQL 16 por serviço, Terraform em `infra/terraform`.

## Commands
| Tarefa | Comando |
|---|---|
| Subir tudo | `docker compose up --build` (raiz) |
| Validar contratos | `make -C contracts check` |
| Lint / typecheck / testes de um serviço | `cd services/<svc> && make lint typecheck test` |
| Testes de integração | `cd services/<svc> && make test-integration` |
| Sincronizar libs/contratos vendorizados | `scripts/sync-shared.sh` (CI: `--check`) |
| Verificar infra AWS / bancos / portas / autonomia | `scripts/verify-infra.sh`, `scripts/verify-platform.sh`, `scripts/check-ports.sh`, `scripts/check-autonomy.sh services/<svc>` |
| Testes das libs de plataforma | `make -C shared/go test test-integration`, `make -C shared/dotnet test test-integration` |

Todos os serviços têm `Makefile`, `Dockerfile` e `.devcontainer/`. Sem o SDK no PATH, o `make` roda no container da linguagem (`TOOLCHAIN=docker`). Bancos: `<db>-db` + job `<svc>-migrate` (Flyway); credenciais de owner só em `db.env.example`.

### Serviços sem código (Java e C#)
**Sem código hoje:** auth, order, inventory (Java) e user (C#). Os donos implementam todo o código; têm código os serviços Go (gateway, notification, autoscaler) e o payment (C#). Cada um desses quatro mantém só o ambiente: `Dockerfile`, `Makefile`, `compose.yaml`, `.devcontainer/`, `pom.xml`/`mvnw` ou `.sln`, `migrations/`, `contracts/`, `*.env.example` e README. Não existe `src/` nem `tests/`.
- **Não crie código de negócio** nesses serviços sem pedido explícito. A lógica descrita neste arquivo e na `docs/` (saga, outbox, consumidores, `platform/` em Java) é o **alvo**, não o estado atual.
- Sem `src/`, `docker build` e o `docker compose up` completo **quebram** nesses serviços; não os trate como regressão.
- **CI:** os workflows dos quatro pulam os passos quando não há `src/` e voltam a rodar sozinhos quando ele existir.
- **Volta do código C#:** o payment já voltou; para o user, descomente `user-service` em `scripts/sync-shared.conf` e rode `scripts/sync-shared.sh` para regerar `Platform/`.
- **Volta do código Java (auth):** a fixture `src/test/resources/platform.sql` deve ser idêntica à de `shared/go/internal/testenv/` (`scripts/check-platform-ddl.sh`).
- Hoje o que responde pelo gateway é só health, as rotas do gateway e o que os Go servem; `/auth/*`, `/me*`, `/events*` e `/orders*` dão 404/502 até os donos entregarem. A collection `docs/postman/` marca com ⏳ o que está pendente.

### Rodar um único teste
Os alvos `make` não aceitam filtro: chame a ferramenta da linguagem direto, com o SDK local (ou dentro do Dev Container do serviço).

| Linguagem | Unitário | Integração |
|---|---|---|
| Go | `go test ./internal/<pkg> -run TestNome` | mesmo comando + `-tags integration` (arquivos `*_integration_test.go`) |
| Java | `./mvnw test -Dtest=Classe#metodo` | rodam no `./mvnw verify` |
| C# | `dotnet test --filter "FullyQualifiedName~Nome&Category!=Integration"` | `--filter "Category=Integration"` (`[Trait("Category", "Integration")]`) |

Testes dos scripts: `scripts/tests/sync-shared.test.sh`. Cenários de falha da demo: `scripts/f1-*.sh` … `scripts/f8-*.sh` (exigem a stack no ar).

## Arquitetura em uma tela
_Descreve o desenho-alvo; os serviços Java e o user ainda não têm código (ver acima)._
- **Entrada única:** só o gateway publica porta; ele valida o JWT e injeta `X-User-Id`, e os demais serviços confiam só nesse header.
- **Saga de compra orquestrada pelo order-service:** `PENDING → AWAITING_PAYMENT → CONFIRMED | CANCELLED`. Toda transição é `UPDATE ... WHERE id = ? AND status = <esperado>`, então evento duplicado ou fora de ordem não regride o estado. `PaymentApproved` para pedido já `CANCELLED` vira `RefundRequested` (status não muda).
- **Publicar:** só pela tabela `outbox`, na mesma transação do efeito; um relay drena com backoff.
- **Consumir:** valida → abre TX → `processed_messages` → efeito + outbox → commit → **só então** ack (RFC 0003 §5.4).
- **Dois roles de banco por serviço:** `<svc>_app` (`DATABASE_URL`, API HTTP, sujeito a RLS via `SET LOCAL app.user_id`) e `<svc>_worker` (`DATABASE_WORKER_URL`, consumidores/relay/jobs, `BYPASSRLS`). Código de consumidor usa o worker; rota HTTP usa o app.
- **Pagamento:** o consumidor grava `payments(PENDING)` e dá ack; um worker (`SKIP LOCKED` + lease) chama o provedor instável depois, fora de transação.
- **Invariante validada no boot:** `RESERVATION_TTL_MS >= ORDER_PAYMENT_TIMEOUT_MS + RESERVATION_SAFETY_MARGIN_MS`. Mudar um desses valores exige ajustar os outros.

## External References
| Need | File |
|---|---|
| Arquitetura, saga, mapa de eventos, matriz R/F | `docs/arquitetura.md` |
| Decisões (ADRs) | `docs/adr/README.md` |
| Envelope e versionamento | `docs/rfc/0001-message-contract-and-event-versioning.md` |
| Auth, JWT, BOLA | `docs/rfc/0002-authentication-and-authorization.md` |
| Retry, DLQ, timeouts, compensação | `docs/rfc/0003-failure-handling-strategy.md` |
| Estrutura de pastas, nomes, erros, logs, migrations, testes | `docs/padroes.md` |
| Schemas de mensagem (fonte da verdade) | `contracts/` |
| Fases e prazos | `docs/roadmap.md` |

## Never
- Acessar o banco de outro serviço; dados alheios só chegam por evento.
- Referenciar `../` fora da pasta do serviço (código, Dockerfile, build): cada `services/<svc>` é autônomo (ADR-0016).
- Editar `services/*/internal/platform/` (Go), `services/*/src/<Projeto>/Platform/` (C#) ou `services/*/contracts/`: são cópias; altere `shared/` ou `contracts/` da raiz e rode `scripts/sync-shared.sh`. (Em Java, `platform/` é código próprio do serviço, não cópia.)
- Publicar no SNS fora do outbox ou dar ack antes do commit.
- Editar migration já aplicada; crie `V<next>__*.sql`.
- Hardcodar endpoint, nome de fila/tópico, credencial, timeout ou taxa: use env.
- Publicar porta de serviço que não seja o gateway.
- Ler identidade do corpo/query; só `X-User-Id` injetado pelo gateway.
- Commitar na `main` ou dar push sem pedido explícito; usar git worktrees.

## Always
- Mensagem nova ou alterada: schema + exemplo + `contracts/asyncapi.yaml` + `make -C contracts check` + `scripts/sync-shared.sh`, no mesmo PR.
- Decisão arquitetural nova: ADR em `docs/adr/` (ADR aceito não se edita; substitua).
- Mudança de schema de banco: migration Flyway em `services/<svc>/migrations/`.
- Commits Conventional com escopo da pasta: `feat(order-service): ...`, `chore(shared): sync ...`.
- Antes de dizer que está pronto: rodar lint, typecheck e testes do serviço e relatar falhas.

## Commit Attribution
Commits feitos por IA DEVEM terminar com:
```
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```
