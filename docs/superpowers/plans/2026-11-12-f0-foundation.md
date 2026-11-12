# F0 Fundação — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `docker compose up` sobe, com um único comando, a infraestrutura AWS emulada (MiniStack + recursos criados por Terraform), a observabilidade (OTel Collector + Jaeger) e um serviço-esqueleto saudável por linguagem. Todos os comportamentos do MiniStack de que a arquitetura depende ficam verificados por spike.

**Architecture:** O `docker-compose.yml` da raiz só faz `include:` de `infra/compose/compose.yaml` e do `compose.yaml` de cada serviço (ADR-0016). O `infra-init` aplica o Terraform contra o MiniStack a cada `up`, com state efêmero (ADR-0011). Quatro serviços reais viram o template de cada linguagem: gateway (Go), auth-service (Java), payment-service (C#) e order-service (TS). Cada um builda a partir da própria pasta e expõe `/health/live` e `/health/ready`.

**Tech Stack:** Docker Compose ≥ 2.20, MiniStack 1.5.40, Terraform 1.16 (providers `hashicorp/aws` ~> 5.0, `hashicorp/tls` ~> 4.0), OTel Collector contrib, Jaeger all-in-one, Go 1.25 (stdlib `net/http`, `log/slog`), Java 21 + Spring Boot 4.0, .NET 10 (minimal API), Node 22 + Fastify 5 + zod + pino + Vitest, GitHub Actions + actionlint.

**Spec:**
- `docs/roadmap.md` §F0
- `docs/arquitetura.md` §2, §4, §9, §10
- `docs/padroes.md` §1, §2, §3.1, §9, §10
- ADR-0009, ADR-0010, ADR-0011, ADR-0015, ADR-0016
- RFC 0003 §5.9

## Global Constraints

- **Branch:** `feat/f0-foundation`, criada a partir de `docs/architecture-foundation`. Nunca commitar na `main`. Sem push.
- **Imagens com versão fixa, nunca `latest`:**

  | Imagem | Tag |
  |---|---|
  | `ministackorg/ministack` | `1.5.40` |
  | `hashicorp/terraform` | `1.16.7` |
  | `jaegertracing/all-in-one` | `1.75.0` |
  | `amazon/aws-cli` | `2.39.7` |
  | `rhysd/actionlint` | `1.7.11` |
  | `postgres` | `16-alpine` |
  | `otel/opentelemetry-collector-contrib` | release estável mais recente com ≥ 2 semanas (sem `nightly`), registrada no compose |

- **AWS:** `AWS_ENDPOINT_URL=http://ministack:4566`, `AWS_REGION=us-east-1`, `AWS_ACCESS_KEY_ID=test`, `AWS_SECRET_ACCESS_KEY=test`.
- **Portas:** só `gateway` publica porta (`8080:8080`). A exceção é `jaeger` (`16686:16686`, ferramenta de operação). Todo serviço escuta em `PORT=8080` internamente.
- **Recursos AWS** (nomes exatos, padrões §3):
  - **Tópicos:** `ingressoja-auth`, `ingressoja-users`, `ingressoja-orders`, `ingressoja-inventory`, `ingressoja-payments`.
  - **Filas:** `ingressoja-gateway`, `ingressoja-auth-service`, `ingressoja-user-service`, `ingressoja-order-service`, `ingressoja-inventory-service`, `ingressoja-payment-service`, `ingressoja-notification-service`, cada uma com `-dlq`, `maxReceiveCount=3` e `VisibilityTimeout=30`.
  - **Bucket:** `ingressoja-receipts`.
  - **Segredo:** `ingressoja/auth/jwt-signing-key` com formato `{"current":{"kid","privatePem"},"previous":null}`.
- **Assinaturas** (filter policy no atributo `type`), copiadas de `docs/arquitetura.md` §7:

  | Fila | Tipos |
  |---|---|
  | gateway | `TokenRevoked` |
  | auth-service | `UserCreationFailed` |
  | user-service | `UserRegistered`, `OrderConfirmed`, `TicketsIssued` |
  | order-service | `InventoryReserved`, `InventoryRejected`, `InventoryReservationExpired`, `PaymentApproved`, `PaymentFailed`, `PaymentRefunded` |
  | inventory-service | `OrderCreated`, `OrderConfirmed`, `OrderCancelled` |
  | payment-service | `PaymentRequested`, `RefundRequested` |
  | notification-service | `TicketsIssued`, `OrderCancelled` |

- **Serviços:** Dockerfile multi-stage com usuário **não-root**. Contexto de build = pasta do serviço, sem `../`. Arquivos obrigatórios por serviço: `README.md`, `Dockerfile`, `.dockerignore`, `compose.yaml`, `.env.example`, `Makefile` com alvos `build test test-integration lint fmt typecheck run` (padrões §1.1–1.2).
- **Config:** `SERVICE_NAME`, `PORT` e `LOG_LEVEL` são obrigatórias e validadas no boot. Faltando alguma, o processo sai com código ≠ 0 e loga o nome da variável.
- **Health:**
  - `GET /health/live` → `200 {"status":"up"}`.
  - `GET /health/ready` → `200 {"status":"up","checks":{}}`. Nesta fase não há banco nem broker, e os checks chegam na F1.
- **Logs:** JSON numa linha, em stdout, com `timestamp`, `level`, `service` e `message`.
- **Commits:** Conventional Commits com escopo (`feat(infra): …`, `feat(gateway): …`), terminando com `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **MiniStack reiniciado perde filas.** `docker compose restart ministack` seguido de `docker compose up -d` precisa recriar tudo: o `infra-init` roda de novo, e o `verify-infra.sh` passa. Teste na Task 2, Step 6.
2. **Porta publicada indevidamente.** `docker compose config` deve mostrar `ports` só em `gateway` e `jaeger`. Teste na Task 8, Step 1 (`scripts/check-ports.sh`).
3. **Serviço que só builda dentro do monorepo.** `scripts/check-autonomy.sh <svc>` copia a pasta para um diretório temporário e roda `make test` + `docker build`. Teste na Task 4, Step 5, reaproveitado nas Tasks 5–7.
4. **Variável de ambiente faltando.** O serviço sai com código ≠ 0 e uma mensagem que contém o nome da variável. É um teste unitário de config em cada template (Tasks 4–7).
5. **Container rodando como root.** `docker image inspect -f '{{.Config.User}}'` não pode ser vazio, `root` nem `0`. Fica dentro do `check-autonomy.sh` (Task 4).

---

## File Structure

```
docker-compose.yml                         # include: infra + serviços
.env.example                               # globais da demo (AWS fake, ARNs/URLs determinísticos)
infra/compose/compose.yaml                 # ministack, infra-init, otel-collector, jaeger, redes
infra/otel/collector.yaml                  # OTLP in → Jaeger out
infra/terraform/{main,variables,outputs,versions}.tf
infra/terraform/modules/messaging/         # tópicos, filas+DLQ, assinaturas
infra/terraform/modules/storage/           # bucket
infra/terraform/modules/secrets/           # chave JWT
scripts/lib/aws.sh                         # função `awsl` (aws-cli em container na rede backend)
scripts/verify-infra.sh                    # asserções sobre recursos AWS
scripts/check-autonomy.sh                  # build/teste fora do monorepo + usuário não-root
scripts/check-ports.sh                     # só gateway/jaeger publicam porta
scripts/wait-healthy.sh                    # espera todos os containers healthy
scripts/spikes/ministack-spike.sh          # comportamentos do MiniStack (PASS/FAIL)
docs/spikes/2026-11-12-ministack.md        # resultado do spike
docs/adr/0018-runtime-versions.md
services/gateway/                          # template Go
services/auth-service/                     # template Java
services/payment-service/                  # template C#
services/order-service/                    # template TS
.github/workflows/root.yml, gateway.yml, auth-service.yml, payment-service.yml, order-service.yml
```

---

### Task 1: Infra base (MiniStack, redes, compose raiz) + helper AWS CLI

**Files:**
- Create: `docker-compose.yml`, `.env.example`, `infra/compose/compose.yaml`, `scripts/lib/aws.sh`, `scripts/wait-healthy.sh`

**Interfaces:**
- Produces:
  - Redes Docker `ingressoja-edge` e `ingressoja-backend`, com nomes fixos via `name:`.
  - Serviço `ministack` com healthcheck em `/_ministack/health`.
  - Função bash `awsl <args...>`, que roda `amazon/aws-cli:2.39.7` na rede `ingressoja-backend` com as envs AWS globais e `--endpoint-url http://ministack:4566`.
  - `scripts/wait-healthy.sh [timeout_s=180]`, que retorna 0 quando todo container do projeto com healthcheck está `healthy`.

- [ ] **Step 1: Criar a branch**

Run: `git checkout docs/architecture-foundation && git checkout -b feat/f0-foundation`
Expected: `Switched to a new branch 'feat/f0-foundation'`

- [ ] **Step 2: Escrever o teste de fumaça (falha)**

Adicionar `scripts/verify-infra.sh` mínimo, com só a primeira asserção: `awsl sts get-caller-identity` retorna JSON com `Account`.

Run: `scripts/verify-infra.sh`
Expected: FAIL (ministack não existe ou não alcançável)

- [ ] **Step 3: Implementar o compose**

- `infra/compose/compose.yaml`: serviço `ministack` (sem `ports`, redes `ingressoja-backend`, healthcheck `wget -qO- http://localhost:4566/_ministack/health || exit 1`, interval 5s, retries 20). Se a imagem não tiver `wget`, usar `python3 -c` com `urllib`. Conferir qual binário existe com `docker run --rm --entrypoint sh ministackorg/ministack:1.5.40 -c 'command -v wget curl python3'`.
- `docker-compose.yml`: `name: ingressoja` + `include: [infra/compose/compose.yaml]`.
- `.env.example`: as envs AWS globais.

- [ ] **Step 4: Rodar**

Run: `cp .env.example .env && docker compose up -d && scripts/wait-healthy.sh && scripts/verify-infra.sh`
Expected: PASS na asserção de `Account`. Anotar o account id retornado, porque ele é usado nos ARNs da Task 2.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml .env.example infra/compose scripts/lib scripts/wait-healthy.sh scripts/verify-infra.sh .gitignore
git commit -m "feat(infra): add MiniStack base compose and aws cli helper"
```

(`.gitignore` ganha `.env` e `infra/terraform/.terraform/`.)

---

### Task 2: Terraform + infra-init

**Files:**
- Create: `infra/terraform/versions.tf`, `main.tf`, `variables.tf`, `outputs.tf`, `modules/messaging/{main,variables,outputs}.tf`, `modules/storage/main.tf`, `modules/secrets/main.tf`
- Modify: `infra/compose/compose.yaml` (serviço `infra-init`), `scripts/verify-infra.sh`, `.env.example`

**Interfaces:**
- Consumes: rede `ingressoja-backend` e serviço `ministack` (Task 1).
- Produces:
  - Serviço compose `infra-init`, que os serviços usam com `depends_on: infra-init: condition: service_completed_successfully`.
  - Variável Terraform `subscriptions: map(list(string))`, com chave = nome do serviço e valor = tipos (tabela de Global Constraints), mais `topic_by_type: map(string)`.
  - Outputs `topic_arns: map(string)` e `queue_urls: map(string)`.
  - Em `.env.example`: `AUTH_TOPIC_ARN`, `USERS_TOPIC_ARN`, `ORDERS_TOPIC_ARN`, `INVENTORY_TOPIC_ARN`, `PAYMENTS_TOPIC_ARN` e `<SERVICO>_QUEUE_URL` por fila (ex.: `ORDER_SERVICE_QUEUE_URL`), com os valores determinísticos que o MiniStack devolve.

- [ ] **Step 1: Escrever as asserções (falha)**

Estender `scripts/verify-infra.sh`:
- **Tópicos:** os 5 existem (`awsl sns list-topics`).
- **Filas:** as 7 + 7 DLQs existem. Cada fila tem `RedrivePolicy` com `maxReceiveCount` 3 apontando para a sua DLQ, e `VisibilityTimeout` = 30.
- **Assinaturas:** cada fila tem assinatura no(s) tópico(s) certo(s), com `FilterPolicy` contendo exatamente os tipos da tabela.
- **Bucket:** `ingressoja-receipts` existe.
- **Segredo:** `ingressoja/auth/jwt-signing-key` existe, e `.current.kid` e `.current.privatePem` são strings não vazias (`jq`).
- **Envs:** cada `*_TOPIC_ARN` e `*_QUEUE_URL` do `.env` bate com o recurso real.

Run: `scripts/verify-infra.sh`
Expected: FAIL em "topic ingressoja-auth not found"

- [ ] **Step 2: Implementar os módulos Terraform**

- Provider `aws` com `endpoints { sns, sqs, s3, secretsmanager, sts = var.aws_endpoint }`, `skip_credentials_validation`, `skip_requesting_account_id`, `skip_metadata_api_check` e `s3_use_path_style = true`.
- `messaging`: `aws_sns_topic` por domínio; `aws_sqs_queue` + DLQ com `redrive_policy`; `aws_sns_topic_subscription` (protocol `sqs`, `filter_policy = jsonencode({type = [...]})`) agrupada por tópico de origem; `aws_sqs_queue_policy` permitindo `sns.amazonaws.com`.
- `secrets`: `tls_private_key` RSA 2048 + `aws_secretsmanager_secret` + `_version` com `jsonencode({current = {kid = "2026-11-12-01", privatePem = …}, previous = null})`.
- **Não ligar raw delivery.** O formato é decidido no spike (Task 3).

- [ ] **Step 3: Serviço `infra-init`**

- Imagem `hashicorp/terraform:1.16.7`, volume read-only `../terraform:/tf`, `working_dir: /work`.
- Entrypoint: `sh -c "cp -r /tf/. /work && terraform init -input=false && terraform apply -auto-approve -input=false && terraform output -json"`. O output fica no log do container.
- State em `/work` (efêmero). Plugins em cache no volume nomeado `tf-plugins` (`TF_PLUGIN_CACHE_DIR`).
- `depends_on: ministack: service_healthy`, `restart: "no"`.

- [ ] **Step 4: Rodar**

Run: `docker compose up -d && docker compose wait infra-init && scripts/verify-infra.sh`
Expected: todas as asserções PASS. Se algum recurso falhar no MiniStack, registrar no spike doc (Task 3) e trocar só aquele recurso por chamada `awsl` num `infra/compose/init-fallback.sh`, com ADR na Task 3.

- [ ] **Step 5: Preencher os ARNs/URLs reais no `.env.example`**

Run: `docker compose logs --no-log-prefix infra-init | sed -n '/^{/,$p'`. Copiar `topic_arns`/`queue_urls` para `.env.example` e rodar `scripts/verify-infra.sh` de novo.
Expected: PASS na asserção de envs

- [ ] **Step 6: Teste de recriação (Review Focus 1)**

Run: `docker compose restart ministack && docker compose up -d && docker compose wait infra-init && scripts/verify-infra.sh`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add infra/terraform infra/compose scripts/verify-infra.sh .env.example
git commit -m "feat(infra): provision SNS, SQS+DLQ, S3 and JWT secret with Terraform"
```

---

### Task 3: Spike de comportamento do MiniStack

**Files:**
- Create: `scripts/spikes/ministack-spike.sh`, `docs/spikes/2026-11-12-ministack.md`
- Create (condicional): `docs/adr/00NN-*.md` para cada plano B acionado — numeração a partir de **0019** (0018 fica reservado para a Task 4) + linha em `docs/adr/README.md`

**Interfaces:**
- Consumes: `awsl`, os recursos da Task 2 e a fila `ingressoja-inventory-service` (assina `OrderCreated`).
- Produces: um documento com veredito PASS/FAIL por item, que decide formato de mensagem e mecanismos para a F1.

O spike usa o próprio script como teste. Cada verificação imprime `PASS <id>` ou `FAIL <id>: <observado>`, e o script retorna 0 só se tudo passar.

- [ ] **Step 1: Escrever o script com as verificações**

| id | Verificação | Fonte |
|---|---|---|
| `S1-filter` | Publicar `type=OrderCreated` em `ingressoja-orders` chega na fila do inventory e **não** na do payment; `type=PaymentRequested` faz o inverso | ADR-0001 |
| `S2-body` | Corpo recebido sem raw delivery é envelope SNS (`Type=Notification`, `Message` = envelope); registrar o formato exato e se `MessageAttributes` aparecem | RFC 0001 §5.2 |
| `S3-raw` | Com `RawMessageDelivery=true` numa assinatura temporária, o corpo é o envelope puro e os atributos chegam como `MessageAttributes` SQS | RFC 0001 §5.2 |
| `S4-receivecount` | `ApproximateReceiveCount` incrementa a cada receive sem delete | RFC 0003 §5.4 |
| `S5-visibility0` | `ChangeMessageVisibility --visibility-timeout 0` torna a mensagem visível de novo na hora | RFC 0003 §5.4 |
| `S6-redrive` | Depois de 3 receives sem delete, a mensagem aparece na DLQ e some da fila | ADR-0001 |
| `S7-depth` | `get-queue-attributes ApproximateNumberOfMessages` reflete N mensagens publicadas | ADR-0015 |
| `S8-pause` | `docker pause` por 30 s + `unpause` mantém filas e mensagens; publicar durante a pausa falha com erro de conexão/timeout (não silencioso) | arquitetura §9 |

- [ ] **Step 2: Rodar**

Run: `scripts/spikes/ministack-spike.sh | tee /tmp/spike.out`
Expected: uma linha PASS/FAIL por id. FAIL não bloqueia o commit, porque o resultado é o entregável.

- [ ] **Step 3: Registrar os resultados**

`docs/spikes/2026-11-12-ministack.md` registra:
- uma tabela id / resultado / observação;
- uma decisão de formato: se `S3` passar, raw delivery fica ligado no Terraform; senão, o consumidor desembrulha o envelope SNS (os dois caminhos continuam suportados, RFC 0001);
- o plano B acionado para cada FAIL:
  - `S6` → mover para a DLQ manualmente (RFC 0003 §9);
  - `S7` → métrica de lag no consumidor (ADR-0015);
  - `S8` → simular a queda por desconexão de rede.

- [ ] **Step 4: ADR por plano B acionado**

Para cada FAIL com plano B, criar um ADR curto (0019, 0020, …) que **substitua apenas o trecho afetado** e adicionar a linha no índice `docs/adr/README.md`. Se `S3` passou, aplicar `raw_message_delivery = true` no módulo messaging e rodar `scripts/verify-infra.sh` de novo (PASS).

- [ ] **Step 5: Commit**

```bash
git add scripts/spikes docs/spikes docs/adr infra/terraform
git commit -m "docs(spikes): record MiniStack behavior spike results"
```

---

### Task 4: Template Go: `services/gateway` + scripts de autonomia

**Files:**
- Create: `services/gateway/{go.mod,cmd/gateway/main.go,internal/app/config.go,internal/app/config_test.go,internal/app/health.go,internal/app/health_test.go,internal/app/server.go,Dockerfile,.dockerignore,compose.yaml,.env.example,Makefile,README.md}`, `scripts/check-autonomy.sh`, `docs/adr/0018-runtime-versions.md`
- Modify: `docker-compose.yml` (`include` do gateway), `docs/padroes.md` §2.3 (".NET 8" → ".NET 10")

**Interfaces:**
- Produces:
  - `app.LoadConfig(getenv func(string) string) (Config, error)`, com `Config{ServiceName string; Port int; LogLevel slog.Level}`. O erro contém o nome da variável faltando ou inválida.
  - `app.NewHealthHandler() http.Handler`, que serve `GET /health/live` e `GET /health/ready`.
  - Subcomando `gateway healthcheck`: faz GET em `http://127.0.0.1:$PORT/health/live` e sai com 0/1.
  - `scripts/check-autonomy.sh <service-dir>`, que retorna 0 se `make test` e `docker build` passam numa cópia em `mktemp -d` **e** a imagem tem `Config.User` diferente de vazio, `root` e `0`.

- [ ] **Step 1: ADR-0018, versões de runtime**

Criar `docs/adr/0018-runtime-versions.md` e a linha correspondente em `docs/adr/README.md`:
- Go 1.25, Java 21 + Spring Boot 4.0, **.NET 10 LTS** e Node 22 LTS.
- Motivo da troca de .NET: o .NET 8 saiu de suporte em 10/11/2026.
- O ADR substitui só a menção a ".NET 8" do ADR-0008, que recebe `Status: Aceito — versão do .NET substituída por ADR-0018`.
- Atualizar `docs/padroes.md` §2.3.

- [ ] **Step 2: Testes falhando**

```go
func TestLoadConfig_MissingPort(t *testing.T)      // getenv sem PORT → err != nil && strings.Contains(err.Error(), "PORT")
func TestLoadConfig_InvalidPort(t *testing.T)      // PORT="abc" → erro contém "PORT"
func TestLoadConfig_Valid(t *testing.T)            // SERVICE_NAME=gateway, PORT=8080, LOG_LEVEL=info → Config{ "gateway", 8080, slog.LevelInfo }
func TestHealthLive(t *testing.T)                  // httptest GET /health/live → 200, body {"status":"up"}, Content-Type application/json
func TestHealthReady(t *testing.T)                 // GET /health/ready → 200, body {"status":"up","checks":{}}
```

Run: `cd services/gateway && go test ./...`
Expected: FAIL (símbolos indefinidos)

- [ ] **Step 3: Implementar config, health, server, main**

- `slog.NewJSONHandler(os.Stdout)` com atributo `service`.
- Shutdown gracioso em `SIGTERM` (`http.Server.Shutdown` com timeout de 10 s).
- O `main` sai com `os.Exit(1)` e loga o erro de config.

- [ ] **Step 4: Testes passam, mais lint**

Run: `make test lint typecheck` (lint = `gofmt -l . | (! grep .)` + `golangci-lint run` via imagem `golangci/golangci-lint` com tag fixa; typecheck = `go vet ./...`)
Expected: PASS

- [ ] **Step 5: Dockerfile, compose, autonomia (Review Focus 3, 5)**

- **Dockerfile:**
  - build: `golang:1.25-alpine`, `CGO_ENABLED=0`;
  - final: `gcr.io/distroless/static-debian12:nonroot`;
  - `USER nonroot:nonroot`, `EXPOSE 8080`.
- **compose.yaml:**
  - serviço `gateway`, `build: .`, `env_file: .env.example`, `ports: ["8080:8080"]`;
  - redes `ingressoja-edge` + `ingressoja-backend`, com os mesmos nomes fixos (`name:`). A forma de declarar fica com quem implementa, mas tem de funcionar no primeiro `docker compose up` a partir de `down -v` (testado na Task 8, Step 4) **e** o `docker compose -f services/gateway/compose.yaml config` tem de ser válido sozinho;
  - `healthcheck: ["CMD","/gateway","healthcheck"]`.

Run: `scripts/check-autonomy.sh services/gateway`
Expected: exit 0, imprimindo `user=nonroot:nonroot`

- [ ] **Step 6: Subir integrado**

Run: `docker compose up -d --build && scripts/wait-healthy.sh && curl -fsS localhost:8080/health/live`
Expected: `{"status":"up"}`

- [ ] **Step 7: Commit**

```bash
git add services/gateway scripts/check-autonomy.sh docker-compose.yml docs/adr docs/padroes.md
git commit -m "feat(gateway): add Go service template with health checks"
```

---

### Task 5: Template Java: `services/auth-service`

**Files:**
- Create: `services/auth-service/{pom.xml,mvnw,.mvn/,src/main/java/br/furb/ingressoja/auth/AuthApplication.java,src/main/java/br/furb/ingressoja/auth/platform/config/AppConfig.java,src/main/java/br/furb/ingressoja/auth/platform/health/HealthController.java,src/main/resources/application.yml,src/test/java/br/furb/ingressoja/auth/platform/config/AppConfigTest.java,src/test/java/br/furb/ingressoja/auth/platform/health/HealthControllerTest.java,Dockerfile,.dockerignore,compose.yaml,.env.example,Makefile,README.md}`
- Modify: `docker-compose.yml`

**Interfaces:**
- Produces:
  - `AppConfig.from(Map<String,String> env)`, que retorna `AppConfig(String serviceName, int port, String logLevel)` ou lança `IllegalStateException` com o nome da variável.
  - `HealthController`: `GET /health/live` e `GET /health/ready` com os corpos de Global Constraints.

- [ ] **Step 1: Testes falhando**

```java
@Test void missingPortFailsNamingVariable()   // assertThatThrownBy(() -> AppConfig.from(Map.of("SERVICE_NAME","auth-service","LOG_LEVEL","info"))).hasMessageContaining("PORT")
@Test void validEnvBuildsConfig()             // port == 8080, serviceName == "auth-service"
@Test void liveReturnsUp()                    // @WebMvcTest: GET /health/live → 200, jsonPath("$.status").value("up")
@Test void readyReturnsUpWithEmptyChecks()    // GET /health/ready → 200, $.checks is empty object
```

Run: `cd services/auth-service && ./mvnw -q test`
Expected: FAIL (compilação)

- [ ] **Step 2: Implementar**

- Spring Boot 4.0 (gerar o `pom.xml` em start.spring.io, só com a dependência `web`).
- `application.yml` com `server.port: ${PORT}` e `logging.structured.format.console: ecs` (logs JSON).
- O `AppConfig` é validado num bean `@PostConstruct` sobre `System.getenv()`.

- [ ] **Step 3: Testes, lint e typecheck**

Run: `make test lint typecheck` (lint = Spotless `check` com google-java-format; typecheck = `./mvnw -q compile -Dmaven.compiler.failOnWarning=true`)
Expected: PASS

- [ ] **Step 4: Dockerfile e autonomia**

- **Dockerfile:**
  - build: `maven:3.9-eclipse-temurin-21`;
  - final: `eclipse-temurin:21-jre-alpine`;
  - usuário `app` (uid 10001) criado no Dockerfile;
  - healthcheck via `wget -qO- localhost:8080/health/live`.
- **compose.yaml:** sem `ports`, rede `ingressoja-backend`.

Run: `scripts/check-autonomy.sh services/auth-service`
Expected: exit 0

- [ ] **Step 5: Commit**

```bash
git add services/auth-service docker-compose.yml
git commit -m "feat(auth-service): add Java service template with health checks"
```

---

### Task 6: Template C#: `services/payment-service`

**Files:**
- Create: `services/payment-service/{PaymentService.sln,Directory.Build.props,src/PaymentService/{PaymentService.csproj,Program.cs,App/AppConfig.cs,App/HealthEndpoints.cs},tests/PaymentService.Tests/{PaymentService.Tests.csproj,AppConfigTests.cs,HealthEndpointsTests.cs},Dockerfile,.dockerignore,compose.yaml,.env.example,Makefile,README.md}`
- Modify: `docker-compose.yml`

**Interfaces:**
- Produces:
  - `AppConfig.FromEnvironment(IDictionary<string,string?> env)`, que retorna `record AppConfig(string ServiceName, int Port, string LogLevel)` ou lança `InvalidOperationException` com o nome da variável.
  - `app.MapHealthEndpoints()`.
  - `public partial class Program {}`, para o `WebApplicationFactory`.

- [ ] **Step 1: Testes falhando** (xUnit)

```csharp
[Fact] void MissingPort_ThrowsNamingVariable()      // Assert.Contains("PORT", ex.Message)
[Fact] void ValidEnv_BuildsConfig()                 // Port == 8080
[Fact] async Task Live_ReturnsUp()                  // WebApplicationFactory<Program>: GET /health/live → 200, {"status":"up"}
[Fact] async Task Ready_ReturnsUpWithEmptyChecks()  // GET /health/ready → 200, checks == {}
```

Run: `cd services/payment-service && dotnet test`
Expected: FAIL (compilação)

- [ ] **Step 2: Implementar**

- .NET 10 minimal API.
- `Directory.Build.props` com `Nullable=enable`, `TreatWarningsAsErrors=true`, `ImplicitUsings=enable` e `RestorePackagesWithLockFile=true` (gera `packages.lock.json`).
- Logs com `builder.Logging.AddJsonConsole()`.

- [ ] **Step 3: Testes, lint e typecheck**

Run: `make test lint typecheck` (lint = `dotnet format --verify-no-changes`; typecheck = `dotnet build -warnaserror`)
Expected: PASS

- [ ] **Step 4: Dockerfile e autonomia**

- **Dockerfile:**
  - build: `mcr.microsoft.com/dotnet/sdk:10.0`;
  - final: `mcr.microsoft.com/dotnet/aspnet:10.0-alpine`;
  - `USER app` (usuário que já vem nas imagens .NET 8+);
  - healthcheck via `wget`.

Run: `scripts/check-autonomy.sh services/payment-service`
Expected: exit 0

- [ ] **Step 5: Commit**

```bash
git add services/payment-service docker-compose.yml
git commit -m "feat(payment-service): add C# service template with health checks"
```

---

### Task 7: Template TypeScript: `services/order-service`

**Files:**
- Create: `services/order-service/{package.json,package-lock.json,tsconfig.json,eslint.config.js,.prettierrc,vitest.config.ts,src/main.ts,src/app/config.ts,src/app/server.ts,src/app/health.ts,tests/unit/config.test.ts,tests/unit/health.test.ts,Dockerfile,.dockerignore,compose.yaml,.env.example,Makefile,README.md}`
- Modify: `docker-compose.yml`

**Interfaces:**
- Produces:
  - `loadConfig(env: NodeJS.ProcessEnv): Config`, com `Config = { serviceName: string; port: number; logLevel: "debug" | "info" | "warn" | "error" }`, validado por zod. O erro de validação lista o nome da variável.
  - `buildServer(config: Config): FastifyInstance`, com as rotas de health registradas.

- [ ] **Step 1: Testes falhando** (Vitest)

```ts
it("fails naming PORT when missing")         // expect(() => loadConfig({ SERVICE_NAME: "order-service", LOG_LEVEL: "info" })).toThrow(/PORT/)
it("rejects invalid LOG_LEVEL")              // LOG_LEVEL: "verbose" → toThrow(/LOG_LEVEL/)
it("parses valid env")                       // port === 8080
it("GET /health/live returns up")            // app.inject → statusCode 200, json() toEqual({ status: "up" })
it("GET /health/ready returns empty checks") // json() toEqual({ status: "up", checks: {} })
```

Run: `cd services/order-service && npm test`
Expected: FAIL (módulos inexistentes)

- [ ] **Step 2: Implementar**

- Fastify 5, logger pino nativo (JSON) com `base: { service }`.
- tsconfig `strict` + `noUncheckedIndexedAccess` + `exactOptionalPropertyTypes`.
- ESLint `typescript-eslint` strict com `no-explicit-any: error`.
- Build com `tsc` para `dist/`.

- [ ] **Step 3: Testes, lint e typecheck**

Run: `make test lint typecheck`
Expected: PASS, sem nenhum `any`

- [ ] **Step 4: Dockerfile e autonomia**

- **Dockerfile:**
  - build: `node:22-alpine` com `npm ci` + `npm run build`;
  - final: `node:22-alpine` com `npm ci --omit=dev`;
  - `USER node`;
  - healthcheck via `wget`.

Run: `scripts/check-autonomy.sh services/order-service`
Expected: exit 0

- [ ] **Step 5: Commit**

```bash
git add services/order-service docker-compose.yml
git commit -m "feat(order-service): add TypeScript service template with health checks"
```

---

### Task 8: Observabilidade, checagem de portas e CI

**Files:**
- Create: `infra/otel/collector.yaml`, `scripts/check-ports.sh`, `.github/workflows/{root,gateway,auth-service,payment-service,order-service}.yml`
- Modify: `infra/compose/compose.yaml` (otel-collector, jaeger), `AGENTS.md` (remover a nota "ainda não existem" dos comandos que agora existem), `.env.example` (`OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317`)

**Interfaces:**
- Consumes: os 4 serviços (Tasks 4–7), `check-autonomy.sh` e `verify-infra.sh`.
- Produces:
  - Collector recebendo OTLP gRPC `4317` e HTTP `4318` e exportando para `jaeger:4317`.
  - `scripts/check-ports.sh`, que retorna 0 se só `gateway` e `jaeger` têm `ports` em `docker compose config --format json`.

- [ ] **Step 1: Testes falhando**

- `scripts/check-ports.sh` (Review Focus 2) deve passar já agora.
- Adicionar ao `verify-infra.sh` a asserção de trace: rodar o container `telemetrygen` (`ghcr.io/open-telemetry/opentelemetry-collector-contrib/telemetrygen`, com a mesma tag do collector) com `traces --otlp-endpoint otel-collector:4317 --otlp-insecure --service f0-smoke --traces 1` e conferir que `curl jaeger:16686/api/services` (via container na rede) contém `f0-smoke`.

Run: `scripts/check-ports.sh && scripts/verify-infra.sh`
Expected: check-ports PASS, verify-infra FAIL em "f0-smoke not found in Jaeger"

- [ ] **Step 2: Implementar collector + jaeger**

- `collector.yaml`: receivers `otlp`, processors `batch`, exporters `otlp/jaeger` (`endpoint: jaeger:4317`, `tls.insecure: true`).
- `jaeger`: `COLLECTOR_OTLP_ENABLED=true`, `ports: ["16686:16686"]`.

Run: `docker compose up -d && scripts/wait-healthy.sh && scripts/verify-infra.sh && scripts/check-ports.sh`
Expected: PASS

- [ ] **Step 3: Workflows de CI**

- `root.yml`:
  - **dispara** em `push` e `pull_request`;
  - **job `contracts`:** `cd contracts && npm ci && npm run validate && npm run typecheck`;
  - **job `compose`:** `docker compose config -q && scripts/check-ports.sh`.
- `<svc>.yml`:
  - **dispara** com `paths: ["services/<svc>/**"]`;
  - **setup:** a linguagem correspondente (`actions/setup-go`, `setup-java` com Temurin 21, `setup-dotnet` 10.0.x, `setup-node` 22);
  - **passos:** `make lint typecheck test` e `docker build services/<svc>`.
- **Actions** fixadas por tag de versão maior (`@v4`/`@v5`).

Run: `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.11 -color`
Expected: sem saída, exit 0

- [ ] **Step 4: Checkpoint F0 completo a partir do zero**

Run: `docker compose down -v && docker compose up -d --build && scripts/wait-healthy.sh && scripts/verify-infra.sh && scripts/check-ports.sh && for s in gateway auth-service payment-service order-service; do scripts/check-autonomy.sh services/$s || exit 1; done`
Expected: tudo exit 0, e `docker compose ps` mostra `ministack`, `otel-collector`, `jaeger` e os 4 serviços `healthy`, com `infra-init` `exited (0)`.

- [ ] **Step 5: Atualizar o AGENTS.md e commitar**

Remover do `AGENTS.md` a frase sobre `Makefile` inexistente. O `sync-shared.sh` continua listado como "a criar na F1".

```bash
git add infra scripts .github AGENTS.md .env.example
git commit -m "feat(infra): add OTel collector, Jaeger, port check and CI workflows"
```
