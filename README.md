# Ticket Pro

Backend distribuído de venda de ingressos, trabalho final de Sistemas Distribuídos da FURB.

- **8 serviços em 4 linguagens:** Go, Java, C# e TypeScript.
- **Comunicação** por eventos (SNS → SQS) numa saga orquestrada, com transactional outbox e consumidores idempotentes.
- **Infra AWS emulada** pelo MiniStack, criada por Terraform.
- **Tudo sobe com um comando.**

## Primeiros passos

### 1. Pré-requisitos

| Ferramenta | Versão | Observação |
|---|---|---|
| Docker | **Docker Desktop** ≥ 4.30 **ou** OrbStack | Precisa do Compose ≥ 2.20 (`docker compose version`). |
| `git`, `make`, `bash` | qualquer | No macOS vêm com o Xcode CLT (`xcode-select --install`). No Windows, use o **WSL2** (Ubuntu) e clone o repo dentro do WSL. |
| SDK da sua linguagem | Go 1.25, Java 21, .NET 10, Node 22 | **Opcional.** Sem ele, o `make` roda tudo em container (veja a seção 4). |

**No Docker Desktop:**

- Em *Settings → Resources*, deixe ≥ 6 GB de memória: a stack completa tem ~25 containers (inventory, order e payment com 2 réplicas cada, um banco por serviço).
- Em *Settings → Advanced*, marque **"Allow the default Docker socket to be used"**. Os testes de integração (Testcontainers) e o modo `TOOLCHAIN=docker` usam `/var/run/docker.sock`.

### 2. Subir a stack

```bash
git clone <repo> ticket-pro && cd ticket-pro
cp .env.example .env
docker compose up -d --build
scripts/wait-healthy.sh 300
```

A primeira subida baixa as imagens e compila os 8 serviços. Leva alguns minutos.

Para conferir:

```bash
docker compose ps
scripts/verify-infra.sh | tail -1
```

O `docker compose ps` deve mostrar os 8 serviços `healthy`. O `verify-infra.sh` termina com `all checks passed`.

| Container | Para quê |
|---|---|
| `gateway` | Único serviço com porta no host: http://localhost:8080 |
| `auth-service`, `user-service`, `order-service`, `inventory-service`, `payment-service`, `notification-service`, `autoscaler` | Serviços internos (rede `ticketpro-backend`), sem porta no host |
| `gateway-redis` | Redis só do gateway (revogação de token e rate limit), sem porta no host |
| `*-db` + `*-migrate` | Um PostgreSQL por serviço e o job Flyway que aplica as migrations (termina com código 0) |
| `ministack` | AWS emulada: SNS, SQS, S3 e Secrets Manager |
| `infra-init` | Roda o Terraform e **termina com código 0**. No Docker Desktop/OrbStack ele aparece parado (vermelho/cinza), e isso é o esperado. |
| `otel-collector` + `jaeger` | Traces. Jaeger UI em http://localhost:16686 |

Para derrubar: `docker compose down` (mantém volumes) ou `docker compose down -v` (zera tudo).

### 3. Pegar o seu serviço

| Serviço | Linguagem | Dono | Comece por |
|---|---|---|---|
| [gateway](services/gateway/README.md) | Go | Pedro | RFC 0002 |
| [inventory-service](services/inventory-service/README.md) | Java | Felipe | ADR-0007, saga |
| [notification-service](services/notification-service/README.md) | Go | Pedro | ADR-0006 |
| [autoscaler](services/autoscaler/README.md) | Go | Pedro | ADR-0015 (F4) |
| [auth-service](services/auth-service/README.md) | Java | Felipe | RFC 0002 |
| [payment-service](services/payment-service/README.md) | C# | Gabriel | RFC 0003 §5.6 |
| [user-service](services/user-service/README.md) | C# | Yasmin | CQRS, BOLA |
| [order-service](services/order-service/README.md) | Java | Matheus | Saga, RFC 0003 |

O README de cada serviço traz responsabilidades, endpoints, eventos, tabelas, o que ler e as próximas tarefas.

**Regra de ouro:** cada `services/<svc>` é tratado como um repositório separado ([ADR-0016](docs/adr/0016-monorepo-with-autonomous-service-folders.md)):

- nada de `../` fora da pasta;
- o serviço nunca acessa o banco de outro;
- dados alheios só chegam por evento.

### 4. Desenvolver sem instalar o SDK

Todo serviço tem os mesmos alvos ([padrões §1.2](docs/padroes.md)):

```bash
cd services/<svc>
make test        # unitários + contrato
make lint        # formatação + linter (modo check)
make typecheck   # checagem de tipos / warnings como erro
make fmt         # formata
make test-integration   # Testcontainers (Postgres, MiniStack)
make run         # sobe só este serviço
```

- **`TOOLCHAIN=docker`:** se o SDK não estiver no `PATH`, o `make` roda o comando dentro da imagem oficial (`golang:1.25`, `eclipse-temurin:21-jdk`, `mcr.microsoft.com/dotnet/sdk:10.0`, `node:22`). Os caches de dependências ficam em volumes `ticketpro-*-cache`. Para forçar mesmo com o SDK instalado: `make test TOOLCHAIN=docker`.
- **Dev Container:** cada serviço tem `.devcontainer/devcontainer.json`. No VS Code (extensão *Dev Containers*), abra **a pasta do serviço** (`services/<svc>`) e escolha *Reopen in Container*. O editor ganha autocomplete e debug com o SDK do container, e os testes de integração usam o Docker do host.
- **Linux sem Docker Desktop:** os arquivos gerados no modo docker (`bin/`, `obj/`, `target/`) ficam com dono `root`. Para corrigir, rode `sudo chown -R "$USER" .` ou use o Dev Container.
- **Contratos:** `make -C contracts check`, com o mesmo `TOOLCHAIN`.

### 5. O que ler, nesta ordem

1. [`docs/arquitetura.md`](docs/arquitetura.md): componentes, saga, mapa de eventos, dados por serviço e matriz requisito → onde.
2. [`docs/padroes.md`](docs/padroes.md): estrutura de pastas por linguagem, nomes, HTTP/erros, logs, migrations, mensageria e testes.
3. [`docs/rfc/`](docs/rfc/INDEX.md):
   - 0001: envelope e versionamento;
   - 0002: auth/JWT/BOLA;
   - 0003: falhas, retry e DLQ.
4. [`docs/adr/`](docs/adr/README.md): as decisões e o porquê de cada uma.
5. [`contracts/`](contracts/README.md): os schemas de cada evento, fonte da verdade das mensagens.
6. [`docs/roadmap.md`](docs/roadmap.md): fases, donos e checkpoints.
7. [`docs/superpowers/plans/`](docs/superpowers/plans/): os planos de cada fase e entrega, tarefa por tarefa.

## Testar e demonstrar

Com a stack no ar, o caminho feliz completo:

```bash
scripts/happy-path.sh   # cadastro → login → pedido → CONFIRMED → ingressos → comprovante no S3
```

O roteiro da apresentação está em [`docs/runbook.md`](docs/runbook.md): o caminho feliz, os cenários de falha F1–F8 e os bônus, com comando, o que observar e o resultado esperado de cada um. Os 8 cenários de uma vez:

```bash
for f in scripts/f[1-8]-*.sh; do $f | tail -1; done
```

## Fluxo de trabalho

- **Branch por tarefa:** `<tipo>/<serviço>-<descrição>`, por exemplo `feat/inventory-service-reservations`. **Nunca** commitar na `main`.
- **Commits** Conventional, em inglês, com escopo da pasta: `feat(order-service): accept orders asynchronously`.
- **Antes do PR**, no seu serviço: `make lint typecheck test test-integration` verde.
- **Mensagem nova ou alterada:** schema + exemplo + `contracts/asyncapi.yaml` + `make -C contracts check`, no mesmo PR.
- **Decisão arquitetural nova:** ADR em `docs/adr/`. ADR aceito não se edita; cria-se outro que o substitui.
- **Mudança de schema de banco:** nova migration `V<next>__*.sql`. Nunca editar uma migration aplicada.
- **Configuração:** tudo por variável de ambiente, validada no boot. Nada de endpoint, fila, credencial ou timeout no código.

As regras completas para devs e agentes de IA estão em [`AGENTS.md`](AGENTS.md).

## Scripts úteis

| Script | Faz |
|---|---|
| `scripts/wait-healthy.sh [timeout]` | Espera todos os containers `healthy` e os jobs one-shot com exit 0 |
| `scripts/verify-infra.sh` | Confere tópicos, filas, DLQs, assinaturas, bucket, segredo e trace no Jaeger |
| `scripts/verify-platform.sh` | Confere, nos 6 bancos, roles (`_app` sem BYPASSRLS, `_worker` com), tabelas `outbox`/`processed_messages` idênticas e readiness com `db: up` |
| `scripts/sync-shared.sh [--check]` | Copia `shared/go`, `shared/dotnet` e `contracts/` para os serviços (ADR-0017); `--check` falha se alguma cópia divergir |
| `scripts/check-ports.sh` | Garante que só o gateway (e o Jaeger) publicam porta |
| `scripts/check-autonomy.sh services/<svc>` | Copia o serviço para fora do repo, roda `make test` e `docker build`, e confere usuário não-root |
| `scripts/happy-path.sh` | Compra de ponta a ponta pelo gateway (R3) |
| `scripts/check-replicas.sh [runs]` | Confere que cada réplica de order, inventory e payment consumiu mensagens (R4) |
| `scripts/f1-*.sh` … `scripts/f8-*.sh` | Cenários de falha F1–F8, com verificação automática ([runbook](docs/runbook.md)) |
| `scripts/dlq.sh list\|show\|redrive <svc>` | Contagem, conteúdo e redrive das DLQs |
| `scripts/gateway-check.sh` | Revogação de token e rate limit, ao vivo |
| `scripts/demo-versioning.sh`, `scripts/demo-autoscale.sh` | Demos dos bônus de versionamento e autoescala |
