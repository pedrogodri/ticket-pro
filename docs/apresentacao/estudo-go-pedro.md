# Estudo: serviços em Go, a linguagem e os slides 4–6

Material de estudo do Pedro para a apresentação. Tudo aqui foi conferido no código (`services/gateway`, `services/notification-service`, `services/autoscaler`, `shared/go`) e nos ADRs.

Ordem: **1** estudar os serviços → **2** como falar deles → **3** por que Go, e os padrões da linguagem → **4** slides 4, 5 e 6.

---

## 1. Os serviços em Go

### 1.1 O que os três têm em comum

São ~4.600 linhas de Go (com testes), 92 testes. Os três seguem o mesmo esqueleto:

```
services/<svc>/
├── cmd/<svc>/main.go     # wiring: monta tudo e liga
├── internal/app/         # config por env, servidor HTTP, health
├── internal/<assunto>/   # um pacote por responsabilidade (proxy, jwtauth, receipts, scaler...)
├── internal/platform/    # COPIADO de shared/go (não se edita; ADR-0017)
└── Dockerfile, Makefile, compose.yaml, contracts/, README.md
```

**O `main.go` sempre faz a mesma sequência:**
1. `healthcheck` como subcomando: a imagem é *distroless* (sem shell nem curl), então o próprio binário faz o probe do container.
2. `app.LoadConfig(os.Getenv)`: lê e valida as variáveis; se faltar uma, morre no boot com o nome dela (*fail fast*).
3. Logger JSON (`log/slog`) e tracing OpenTelemetry.
4. `signal.NotifyContext(SIGINT, SIGTERM)`: um `context` que é cancelado quando o Docker manda parar.
5. Monta as dependências à mão (sem framework de injeção) e sobe as goroutines (consumidor, loop).
6. `app.Run`: servidor HTTP; quando o contexto cancela, `srv.Shutdown` com 10 s para terminar o que está em voo.

**Padrões repetidos (bom para citar):**
- **Config por env, validada:** o `reader` guarda o primeiro erro, e `LoadConfig` fica parecendo uma declaração. Nada hardcodado.
- **Interfaces pequenas, definidas por quem usa:** `TokenVerifier`, `RevocationChecker`, `RateLimiter` (gateway); `Queue`, `Docker` (autoscaler); `Store` (notification). Nos testes entra um *fake*, sem framework de mock.
- **Relógio injetável** (`Clock func() time.Time`): testes de TTL, cooldown e rate limit sem `sleep`.
- **Health:** `/health/live` (processo vivo) e `/health/ready` (dependências: Redis, broker, banco).
- **Dockerfile multi-stage:** compila com `CGO_ENABLED=0` (binário estático) e copia só o binário para `distroless/static:nonroot`. Imagem de poucos MB, sem root.
- **Qualidade:** `make lint` (gofmt + golangci-lint), `make typecheck` (`go vet`), `make test`, `make test-integration` (build tag `integration`).

### 1.2 Gateway (a porta de entrada)

**Papel:** único serviço com porta publicada (`:8080`). Tudo que vem de fora passa por ele. Sem banco; só um Redis próprio.

**Caminho de uma requisição** (`internal/proxy/proxy.go`, em ordem):

| # | O que faz | Resposta se falhar |
|---|---|---|
| 1 | Gera um `correlationId` novo (o do cliente vira `X-Client-Request-Id`) | — |
| 2 | **Apaga** `X-User-Id`, `X-User-Email` e `X-Internal-*` vindos do cliente | — |
| 3 | Roteia pelo 1º segmento do path (`GATEWAY_ROUTES`: `orders=http://order-service:8080,...`) | 404 (nunca encaminha) |
| 4 | Corpo maior que `GATEWAY_MAX_BODY_BYTES` (64 KiB) | 413 |
| 5a | Rota pública (`register`, `login`, `refresh`): rate limit **por IP** (login mais estrito: 1 req/s, rajada 5) | 429 + `Retry-After` |
| 5b | Rota protegida: valida o JWT | 401 (e conta no bucket `authfail:<ip>` → 429) |
| 6 | Token revogado? `EXISTS revoked:{jti}` no Redis | 401 |
| 7 | Rate limit **por usuário** (`POST /orders`: 2 req/s, rajada 5; demais 10/20) | 429 |
| 8 | **Injeta** `X-User-Id = sub` do token | — |
| 9 | `httputil.ReverseProxy` encaminha, com `traceparent` e timeout | 504 (timeout) / 502 (fora) |

Erros saem em `application/problem+json` (RFC 9457), título em PT-BR e `correlationId`.

**Validação do JWT** (`internal/jwtauth`):
- Aceita **só RS256** (bloqueia `alg: none` e HS256, ataque clássico de troca de algoritmo).
- Confere `kid`, assinatura, `iss`, `aud`, `exp` obrigatório, tolerância de relógio de 30 s.
- **JWKS em cache** (300 s), protegido por `sync.Mutex`. `kid` desconhecido força refetch, mas **no máximo 1 a cada 10 s**: rotação de chave funciona e ninguém derruba o auth mandando tokens com `kid` aleatório.
- Resultado: o gateway valida o token **sem chamar o auth** a cada requisição (ADR-0002).

**Rate limit** (`internal/ratelimit`): *token bucket* num **script Lua** no Redis. O Lua roda atômico, então várias réplicas do gateway dividem o mesmo balde sem condição de corrida. O relógio vem do gateway; `max(now, ts)` impede que uma réplica com relógio atrasado reabasteça o balde duas vezes. A chave expira sozinha quando o balde já estaria cheio.

**IP do cliente:** o do peer TCP. `X-Forwarded-For` é ignorado: o gateway é a borda, então esse header viria do próprio cliente, que escolheria o próprio balde.

**Revogação** (`internal/revocation`, ADR-0014): o auth publica `TokenRevoked`; o gateway consome da fila `ticketpro-gateway` e grava `revoked:{jti}` com TTL até o token expirar. Não usa `processed_messages` porque `SET` com o mesmo TTL já é idempotente. Mensagem inválida → visibility 0 → DLQ; erro de Redis → retry com backoff; ack só depois do `SET`.

**Fail-open:** se o Redis cair, revogação e rate limit são pulados com log. O `redisGuard` (com `atomic`) suspende as checagens por 5 s após uma falha, para não pagar 200 ms de timeout em toda requisição. Justificativa: o risco máximo é o TTL de 15 min do access token; fail-closed derrubaria o sistema inteiro por causa de um cache.

**Prova:** `scripts/gateway-check.sh` → token revogado dá 401; logins repetidos dão 429 com `Retry-After`.

### 1.3 Notification-service (comprovante no S3)

**Papel:** consome `TicketsIssued` e `OrderCancelled`. Grava o comprovante no S3 e registra a notificação no banco. **Não publica nada**, então não tem outbox.

**Usa o consumidor da plataforma** (`shared/go/messaging/consumer.go`), que é o coração da idempotência em Go. Fluxo de cada mensagem (RFC 0003 §5.4):

```
recebe (long poll) → valida envelope (JSON Schema) → existe handler p/ type+version?
  não → POISON: visibility 0 → após 3 recebimentos vai para a DLQ
  sim → abre TX (pool worker) → INSERT processed_messages ON CONFLICT DO NOTHING
          já existia → rollback + ack (DUPLICATA)
          novo → handler → commit → só então ack
          erro transitório → rollback, visibility = backoff exponencial com jitter
```

- **Concorrência:** recebe até 4 mensagens e processa cada uma numa goroutine (`sync.WaitGroup`). `context.WithoutCancel` deixa as que estão em voo terminarem no shutdown; as sem ack voltam para a fila.
- **`safeHandle`:** `recover` de panic no handler vira erro transitório, então um bug num evento não derruba o processo.

**O truque do S3:** o S3 não participa da transação do Postgres. Então o handler grava **primeiro no S3**, com chave **determinística** `receipts/{orderId}.json`, e depois faz `INSERT notifications ... ON CONFLICT (order_id, kind) DO NOTHING` na transação. Se o serviço morrer entre os dois, a mensagem volta e regrava **o mesmo objeto**. Reprocessar não duplica.

**Outros detalhes:** dois roles de banco (`notifications_app` com RLS, `notifications_worker` com BYPASSRLS; o consumidor usa o worker). Pools conectam *lazy*: o serviço sobe mesmo com o banco subindo, e o `/health/ready` avisa. S3 em *path-style* porque o MiniStack exige.

**Por que ele importa na banca:** é o exemplo de **evento com 2 consumidores** (requisito obrigatório): `TicketsIssued` vai para ele e para o user-service.

**Prova:** `scripts/notification-check.sh` → mesmo evento 5 vezes = 1 comprovante, 1 notificação.

### 1.4 Autoscaler (escala por fila)

**Papel:** bônus de autoescala (ADR-0015). Sem banco, sem plataforma.

**Loop** (`internal/scaler/loop.go`), a cada 5 s (`time.Ticker`), para cada alvo (`payment-service=ticketpro-payment-service`):
1. **Profundidade** = `ApproximateNumberOfMessages + ApproximateNumberOfMessagesNotVisible` (inclui as que estão sendo processadas: é o trabalho total).
2. **Alvo** = `clamp(ceil(fila / 5), 2, 5)`. Em inteiro: `(depth + 5 - 1) / 5`.
3. **Histerese** (`State.Decide`): subir é **imediato**; descer só depois de **30 s** com o alvo menor estável. Evita o "pisca-pisca" de réplicas entre rajadas.
4. Aplica pela **API do Docker**.

**Cliente Docker próprio** (`internal/docker/client.go`): HTTP sobre o socket unix, sem o SDK do Docker (de propósito: 4 chamadas não justificam a dependência).
- **Clone:** faz `inspect` de uma réplica existente e cria outra com a mesma imagem, env, comando, healthcheck e redes, com o **alias DNS** do serviço e as **labels do compose** (aparece no `docker compose ps`). Se falhar no meio, apaga o meio-clone, senão o próximo clone com o mesmo nome dá 409.
- **Remove:** `stop?t=10` (SIGTERM e 10 s de graça; mensagens em voo voltam para a fila pela visibility) e `delete`. Remove do número mais alto para o mais baixo: as réplicas do compose são as últimas a sair.
- Se o serviço tem 0 réplicas, não faz nada: subir o serviço é papel do compose.

**Limitação honesta:** montar `/var/run/docker.sock` dá controle total do Docker do host. Aceitável na demo, não em produção (lá seria KEDA/HPA).

**Prova:** pausa o payment, acumula 40 `PaymentRequested` → sobe para 5 réplicas → fila drena → volta para 2 após o cooldown.

---

## 2. Como falar dos serviços Go

Regra: **papel → como funciona → por que assim → prova**. Não leia código; cite uma decisão por serviço.

### Gateway (~1 min)
> "O gateway, que eu fiz em Go, é a única porta pública: só ele publica a 8080, os outros sete ficam numa rede privada. Ele faz três coisas. Primeiro, **identidade**: valida o JWT RS256 localmente, com a chave pública do auth em cache, sem chamar o auth a cada requisição; apaga qualquer X-User-Id que o cliente mande e injeta o do token. Os serviços confiam só nesse header. Segundo, **proteção**: rate limit por IP nas rotas públicas e por usuário nas autenticadas, com um token bucket atômico no Redis, além de limite de corpo e timeout. Terceiro, **revogação**: o auth publica TokenRevoked, o gateway guarda o jti no Redis e o token dá 401 antes de expirar. Se o Redis cair, ele segue no ar e loga: escolhemos disponibilidade, com risco limitado aos 15 minutos do token."

### Notification (~40 s)
> "O notification consome TicketsIssued e grava o comprovante no S3. O ponto é a idempotência com um recurso que não é transacional: ele grava primeiro no S3, com uma chave determinística, receipts/orderId.json, e depois registra no banco junto com o processed_messages. Se cair no meio, a mensagem volta e regrava o mesmo arquivo. No teste, o mesmo evento cinco vezes gera um comprovante só. Ele também é o nosso exemplo de evento com dois consumidores: o TicketsIssued vai para ele e para o user."

### Autoscaler (~40 s)
> "O autoscaler olha a fila, não a CPU, porque o gargalo do payment é o provedor lento. A cada 5 segundos ele lê a profundidade da fila e calcula uma réplica para cada 5 mensagens, entre 2 e 5. Subir é imediato; descer espera 30 segundos estável, para não ficar oscilando. Ele clona uma réplica existente pela API do Docker, com as mesmas redes e labels, então ela aparece no docker compose ps. Na demo: 40 mensagens, sobe para 5, drena e volta para 2."

### Frase-resumo dos três
> "Os três serviços em Go são os de infraestrutura: a borda, a notificação e a escala. São pequenos, sem estado de negócio, e Go encaixa nisso."

### Perguntas prováveis

| Pergunta | Resposta curta |
|---|---|
| Por que o gateway não chama o auth para validar? | Latência e acoplamento: se o auth cair, ninguém usaria o sistema. Com JWKS em cache a validação é local (ADR-0002). |
| E se trocarem a chave do auth? | `kid` desconhecido força refetch do JWKS, limitado a 1 a cada 10 s. |
| Por que fail-open no Redis? | Cache fora não pode derrubar o sistema; o risco é limitado ao TTL de 15 min do token (ADR-0014). |
| Por que ignorar X-Forwarded-For? | O gateway é a borda; esse header viria do cliente e ele escolheria o próprio balde de rate limit. |
| Duas réplicas do gateway dividem o rate limit? | Sim: o balde fica no Redis e o script Lua é atômico. |
| Como o notification não duplica comprovante? | Chave determinística no S3 + `processed_messages` + `UNIQUE (order_id, kind)`. |
| Por que escalar por fila e não por CPU? | O payment espera o provedor (I/O); a CPU fica baixa com a fila crescendo. |
| O que acontece com mensagens na réplica removida? | SIGTERM, 10 s de graça; o que não recebeu ack volta para a fila pela visibility e outra réplica pega. |
| Por que não usar o SDK do Docker? | São 4 chamadas HTTP; o cliente mínimo evita uma dependência grande. |
| Por que não KEDA? | Não temos Kubernetes, só Compose. KEDA está nos próximos passos. |

---

## 3. Go: por que escolhemos e os padrões da linguagem

### 3.1 Por que Go (ADR-0008)

O enunciado exige 3 linguagens. A divisão seguiu **quem domina cada linguagem** e **o perfil do serviço**. Os três serviços Go são de infraestrutura, e Go foi feito para isso:

| Motivo | Onde aparece no projeto |
|---|---|
| **Proxy reverso pronto na stdlib** (`net/http/httputil.ReverseProxy`) | Gateway sem framework web |
| **Concorrência barata** (goroutines, poucos KB cada) | Consumidor processa mensagens em paralelo; servidor HTTP, consumidor e loop rodam juntos |
| **`context.Context`** para cancelamento e timeout | SIGTERM para tudo em cascata; timeouts de Redis, upstream, AWS |
| **Binário estático, imagem minúscula, boot rápido** | `distroless/static`; o autoscaler clona réplicas que sobem em segundos |
| **Baixa latência e pouca memória** | O gateway está no caminho de toda requisição |
| **Ecossistema de nuvem/infra** | Docker, Kubernetes e o próprio SDK da AWS v2 existem em Go |
| **Simples e opinativo** (`gofmt`, `go vet`, uma forma de fazer) | Código uniforme entre os três serviços |
| **Quem fez domina** | Pedro |

**Frase para a banca:**
> "Usamos cada linguagem onde ela é mais forte. Java ficou com os serviços de domínio mais transacionais, a saga e o estoque, por causa do Spring e do @Transactional. C# ficou com o pagamento e o perfil. Go ficou com a infraestrutura: a borda, que precisa de baixa latência, e os serviços pequenos de consumo e escala. Isso só funciona porque o contrato das mensagens é único, em JSON Schema e AsyncAPI: qualquer serviço poderia ser reescrito em outra linguagem sem os outros perceberem. Isso aconteceu de verdade: o order e o inventory mudaram de linguagem no meio do projeto (ADR-0021) e nada mais mudou."

### 3.2 Arquitetura e padrões de Go usados

| Padrão | O que é | Exemplo no código |
|---|---|---|
| **`cmd/` + `internal/`** | Layout padrão; o compilador **proíbe** importar `internal/` de fora do módulo | todos os serviços |
| **Pacote por responsabilidade** | Não por camada (`controllers/`, `services/`); cada pacote é um assunto | `proxy`, `jwtauth`, `ratelimit`, `revocation`, `receipts`, `scaler` |
| **Interfaces implícitas e pequenas** | Um tipo satisfaz a interface só por ter os métodos; a interface fica em quem **usa** ("aceite interfaces, retorne structs") | `RateLimiter`, `Queue`, `Docker`, `Store` |
| **Composição no `main`** | Dependências montadas à mão, sem DI nem framework | `cmd/gateway/main.go` |
| **Erros são valores** | `if err != nil`, embrulhar com `%w`, testar com `errors.Is/As`, erros-sentinela | `ErrInvalidToken`, `ErrStale`, `ErrPoison`, `errNotFound` |
| **`context` em tudo** | Cancelamento, prazo, trace e correlationId viajam juntos | `signal.NotifyContext`, `WithTimeout`, `WithoutCancel` |
| **Goroutines + WaitGroup + channels** | Concorrência estruturada | consumidor (`wg`), servidor (`errCh`) |
| **`sync.Mutex` / `atomic`** | Estado compartilhado entre goroutines | cache do JWKS (mutex), `brokerUp` e `redisGuard` (atomic) |
| **Graceful shutdown** | Para de aceitar, termina o que está em voo | `srv.Shutdown(10s)` + `wg.Wait()` |
| **`defer`** | Limpeza garantida | `defer rows.Close()`, `defer tx.Rollback()` (no-op após commit) |
| **`recover` só na fronteira** | Panic vira erro num único ponto | `safeHandle` do consumidor |
| **Logs estruturados** | `log/slog` em JSON, com `correlationId` e `traceId` | `app.NewLogger` |
| **Testes da stdlib** | `*_test.go` ao lado do código, table-driven, fakes por interface, relógio injetável | `decide_test.go`, `limiter_test.go` |
| **Integração com build tag** | `//go:build integration` + Testcontainers (Postgres real) | `receipts_integration_test.go` |

**Plataforma compartilhada** (`shared/go`, ADR-0017): `envelope` (formato e validação com JSON Schema), `messaging` (consumidor com ack depois do commit), `outbox` (relay com `SKIP LOCKED` + lease), `idempotency` (`processed_messages`), `retry` (backoff exponencial com *full jitter*: `random(0, min(30s, 200ms·2^n))`), `logging`, `tracing`. É escrita e testada uma vez e **copiada** para cada serviço por `scripts/sync-shared.sh`, porque cada serviço precisa compilar sozinho da própria pasta (ADR-0016). O CI falha se a cópia divergir.

---

## 4. Slides 4, 5 e 6 do Canva

### 4.1 Panorama: por que esses três slides existem

Os slides 1–3 dizem **o problema** (vender cada lugar uma vez, mesmo com concorrência e falha). Os slides 7 em diante descem em **cada serviço**. Os slides 4–6 são a ponte: a visão do sistema inteiro antes dos detalhes.

A sequência tem uma lógica que vale dizer em voz alta:

| Slide | Pergunta que responde | Ideia |
|---|---|---|
| **4 · Stack compartilhada** | *Com o quê* construímos? | A base técnica é igual para os 8 serviços, qualquer que seja a linguagem |
| **5 · Padrões comuns** | *Quais regras* todo serviço obedece? | As regras que garantem a corretude num sistema distribuído |
| **6 · Arquitetura** | *Como* as peças conversam? | O desenho: síncrono pela borda, assíncrono por eventos, e a saga |

Frase de transição para abrir o bloco:
> "Antes de entrar em cada serviço, três telas de visão geral: o que todos usam, as regras que todos seguem e como eles conversam."

### 4.2 Slide 4: "O que todos os serviços usam"

**Por quê:** mostrar que 3 linguagens não viraram 3 sistemas. A infraestrutura, a mensageria, a observabilidade e o CI são os mesmos.

| Cartão | O que dizer |
|---|---|
| Docker Compose | Um `docker compose up` sobe tudo (~25 containers); cada serviço traz o seu `compose.yaml`, e a raiz só inclui. |
| MiniStack | AWS emulada local: SNS, SQS, S3 e Secrets Manager na porta 4566. Reproduzível e sem custo. |
| Terraform | Infra como código: o container `infra-init` cria tópicos, filas, DLQs, bucket e segredo e termina. |
| PostgreSQL 16 | Um banco por serviço, em rede própria; schema versionado com Flyway. |
| SNS → SQS | Um tópico por domínio, uma fila e uma DLQ por consumidor (fan-out). |
| OpenTelemetry | Collector + Jaeger; o `traceparent` viaja no HTTP e nos atributos da mensagem. |
| Libs + contratos | `shared/go` e `shared/dotnet` copiados para cada serviço; JSON Schema e AsyncAPI como fonte da verdade. |
| GitHub Actions | Um workflow por serviço: lint, tipos, testes e build. |

**Fala (~1 min):**
> "Oito serviços em três linguagens, mas uma base só. Tudo sobe com um docker compose up. A nuvem é a AWS emulada pelo MiniStack, com SNS, SQS, S3 e Secrets Manager, e os recursos são criados por Terraform num container de inicialização. Cada serviço tem o seu Postgres. A comunicação assíncrona é SNS para SQS, com um tópico por domínio e uma fila com DLQ por consumidor. O tracing é OpenTelemetry com Jaeger, e o mesmo trace atravessa Go, Java e C#. O que garante que as linguagens se entendem são os contratos em JSON Schema e AsyncAPI, e, em Go e C#, uma biblioteca de plataforma escrita uma vez e copiada para cada serviço. E cada serviço tem o seu pipeline de CI."

### 4.3 Slide 5: "Padrões que todo serviço segue"

**Por quê:** num sistema distribuído a mensagem pode chegar duplicada, fora de ordem ou não chegar, e o serviço pode cair a qualquer momento. Esses cinco padrões são a resposta. São eles que fazem os testes F1–F8 passarem.

| Cartão | O que significa | Problema que resolve |
|---|---|---|
| Saga orquestrada | O order-service conduz a compra; cada transição é `UPDATE ... WHERE status = <esperado>` | Sem transação distribuída; evento duplicado ou atrasado não faz o estado voltar |
| Transactional outbox | O evento é gravado na mesma transação do dado; um relay publica depois | Gravar no banco e publicar no broker não são atômicos; com outbox, broker fora não perde nada |
| Consumidor idempotente | `processed_messages` na mesma transação do efeito; ack só depois do commit | O SQS entrega *pelo menos uma vez*; duplicata vira no-op |
| Banco por serviço | Ninguém lê o banco de outro; roles `app` (RLS) e `worker` | Acoplamento e falha em cascata; dado alheio só por evento |
| Gateway como entrada | Valida o JWT via JWKS e injeta `X-User-Id` | Ninguém escolhe a própria identidade; só uma porta pública |

**Canva (corrigido em 25/11):** os textos dos cartões "Transactional outbox" (tinha lixo: `OEvento ... nada.mbckff`) e "Consumidor idempotente" (estava com o texto do gateway) foram corrigidos. Atrás do cartão "Consumidor idempotente" ainda há dois cartões antigos empilhados ("Consumo idempotente" e "OpenTelemetry"), escondidos pelo da frente.

**Fala (~1 min 15):**
> "Num sistema distribuído, a mensagem pode chegar duas vezes, fora de ordem, ou o serviço pode cair no meio. Cinco regras resolvem isso, e todo serviço segue. A compra é uma saga orquestrada pelo order, e o estado só avança: cada transição é um UPDATE condicional no estado esperado. Para publicar, ninguém fala direto com o broker: o evento vai para uma tabela outbox na mesma transação do dado, e um relay publica depois, então broker fora não perde nada. Para consumir, gravamos o id da mensagem na mesma transação do efeito e só damos ack depois do commit: entrega duplicada, efeito único. Cada serviço tem o próprio banco e ninguém lê o do outro. E a identidade só entra pelo gateway, que injeta o X-User-Id."

### 4.4 Slide 6: "Como os microsserviços conversam"

**Por quê:** é o mapa do sistema. Tudo o que vem depois (gateway, auth, order, inventory, payment, escala) é um pedaço deste desenho.

**Como ler o diagrama, da esquerda para a direita:**
1. **Cliente** (scripts/Postman) → **gateway**: a única porta.
2. **Coluna síncrona (HTTP via gateway):** auth, user, order, inventory. São os que têm API.
3. **Centro, SNS → SQS:** o barramento. Tópico por domínio, fila + DLQ por consumidor, publica só via outbox, ack só depois do commit.
4. **Coluna "só eventos":** payment e notification não têm API de negócio, só consomem e publicam eventos.
5. **Faixa de baixo:** a saga em 7 passos: `POST /orders` → `OrderCreated` → `InventoryReserved` → `PaymentRequested` → `PaymentApproved` → `OrderConfirmed` → `TicketsIssued`. Falha ou prazo → `OrderCancelled` e o estoque volta; aprovação tardia → `RefundRequested` e estorno.

**Cuidado ao falar:** na coluna "só eventos" estão também **observabilidade** e **autoscaler**, mas eles não consomem eventos de negócio. O OpenTelemetry recebe traces de todos; o autoscaler só **lê a profundidade** das filas. Diga isso para ninguém perguntar "que evento o autoscaler consome?".

**Fala (~1 min 30):**
> "Este é o mapa. O cliente fala só com o gateway. Atrás dele, quatro serviços têm API HTTP: auth, user, order e inventory. Mas a compra não acontece por HTTP entre serviços; acontece por eventos. No centro está o SNS para SQS: cada serviço publica pelo outbox, e cada consumidor tem a sua fila com DLQ. Payment e notification não têm API nenhuma, só eventos. Do lado, o OpenTelemetry recebe os traces de todos, e o autoscaler lê o tamanho das filas para escalar. Embaixo está a saga: o cliente faz POST e recebe 202 na hora; o order publica OrderCreated, o inventory reserva, o order pede o pagamento, o payment aprova, o order confirma e o inventory emite os ingressos, que vão para o user e para o notification. Se o pagamento falha ou passa do prazo, o pedido é cancelado e o estoque volta; se a aprovação chega tarde, vira estorno. E cada serviço tem o seu banco, numa rede própria: nenhum lê o banco do outro."

**Perguntas prováveis sobre o slide 6:**

| Pergunta | Resposta |
|---|---|
| Por que orquestração e não coreografia? | Um lugar só guarda o estado e o prazo do pedido: compensar e depurar fica simples (ADR-0003). |
| Por que os serviços não se chamam por HTTP? | Acoplamento temporal: se o payment estiver lento, o pedido travaria. Com eventos, o POST responde 202 na hora. |
| Por que SNS + SQS e não só SQS? | SNS faz o fan-out: um evento, várias filas (ex.: `TicketsIssued` → user e notification). |
| O que é a DLQ? | Fila de mensagens que falharam 3 vezes (`maxReceiveCount=3`); não trava a fila principal. |
