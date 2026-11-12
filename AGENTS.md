# Agent Instructions — IngressoJá

Sistema distribuído acadêmico (FURB). Só backend. Respostas em PT-BR; código, identificadores, commits e branches em inglês.

## Stack
| Serviço | Linguagem | Pasta |
|---|---|---|
| gateway, inventory, notification, autoscaler | Go | `services/{gateway,inventory-service,notification-service,autoscaler}` |
| auth | Java (Spring Boot) | `services/auth-service` |
| payment, user | C# (.NET 10) | `services/{payment-service,user-service}` |
| order | TypeScript (Node 22, strict) | `services/order-service` |

Infra: MiniStack (`AWS_ENDPOINT_URL=http://ministack:4566`), SNS→SQS+DLQ, S3, Secrets Manager, PostgreSQL 16 por serviço, Terraform em `infra/terraform`.

## Commands
| Tarefa | Comando |
|---|---|
| Subir tudo | `docker compose up --build` (raiz) |
| Validar contratos | `cd contracts && npm ci && npm run validate && npm run typecheck` |
| Lint / typecheck / testes de um serviço | `cd services/<svc> && make lint typecheck test` |
| Testes de integração | `cd services/<svc> && make test-integration` |
| Sincronizar libs/contratos vendorizados | `scripts/sync-shared.sh` (CI: `--check`) |
| Verificar infra AWS / portas / autonomia | `scripts/verify-infra.sh`, `scripts/check-ports.sh`, `scripts/check-autonomy.sh services/<svc>` |

Serviços com `Makefile` hoje: gateway, auth-service, payment-service, order-service. `scripts/sync-shared.sh` é criado na F1 (ADR-0017).

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
- Editar `internal/platform/`, `src/Platform/` ou `contracts/` dentro de um serviço: são cópias; altere `shared/` ou `contracts/` da raiz e rode `scripts/sync-shared.sh`.
- Publicar no SNS fora do outbox ou dar ack antes do commit.
- Editar migration já aplicada; crie `V<next>__*.sql`.
- Hardcodar endpoint, nome de fila/tópico, credencial, timeout ou taxa: use env.
- Publicar porta de serviço que não seja o gateway.
- Ler identidade do corpo/query; só `X-User-Id` injetado pelo gateway.
- Usar `any` em TypeScript.
- Commitar na `main` ou dar push sem pedido explícito; usar git worktrees.

## Always
- Mensagem nova ou alterada: schema + exemplo + `contracts/asyncapi.yaml` + `npm run validate`, no mesmo PR.
- Decisão arquitetural nova: ADR em `docs/adr/` (ADR aceito não se edita; substitua).
- Mudança de schema de banco: migration Flyway em `services/<svc>/migrations/`.
- Commits Conventional com escopo da pasta: `feat(order-service): ...`, `chore(shared): sync ...`.
- Antes de dizer que está pronto: rodar lint, typecheck e testes do serviço e relatar falhas.

## Commit Attribution
Commits feitos por IA DEVEM terminar com:
```
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```
