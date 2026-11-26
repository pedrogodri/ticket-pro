# Guia da demo ao vivo — Ticket Pro (25/11)

Roteiro de operação: **o que rodar, em que ordem, onde mostrar o resultado e o que falar**. Complementa o [roteiro dos slides](roteiro.md) (slide 22 em diante) e o [runbook](../runbook.md).

Regra de ouro do enunciado: *"provocar a falha ao vivo e mostrar, pelos logs e pelo estado dos bancos, que o sistema se comportou como esperado"*, e **prever antes de executar**. O `scripts/demo.sh` faz isso sozinho em 3 tempos: **previsão** (espera Enter) → **execução** (script canônico) → **evidência** (bancos + logs + link do Jaeger). Leia a previsão em voz alta antes do Enter.

---

## 0. Preparação (15 min antes de entrar)

```bash
scripts/tools.sh down
```

```bash
docker compose down -v
```

```bash
docker compose up -d --build
```

```bash
scripts/wait-healthy.sh 300
```

```bash
scripts/verify-infra.sh | tail -1
```

```bash
scripts/demo.sh all
```

```bash
scripts/tools.sh up
```

```bash
scripts/demo-terminals.sh --browser
```

- O `down -v` + `up` **é a prova do bônus de IaC** (recria tudo do zero). Guarde a saída de `docker compose logs infra-init | grep -E "Apply complete"` (seção 6.2).
- `demo.sh all` aquece a JVM/.NET e confirma **13/13** (~7 min). Se algo falhar aqui, você descobre antes da banca.
- `tools.sh down` vem **antes** do `compose down`: as ferramentas visuais ficam ligadas às redes da stack e impediriam o compose de removê-las. O `tools.sh up` sobe pgAdmin e RedisInsight (seção 5.2).
- Depois do `all`, a stack volta ao estado normal (os scripts restauram réplicas e envs).

### Layout de tela (deixe pronto)

Um comando abre tudo no **Terminal do Mac**: três janelas lado a lado e, com `--browser`, as páginas.

```bash
scripts/demo-terminals.sh --browser
```

| Janela | Conteúdo | Para quê |
|---|---|---|
| **T1 demo** (esquerda, fonte grande) | `scripts/demo.sh` (menu) | operar a demo. Cada passo é **narrado em ciano**: `→ POST /orders {…}` / `← 202 {…}`, `SNS publish → …`, `docker pause …`, consultas aos bancos. Polling repetido só aparece quando muda (ex.: `PENDING` → `AWAITING_PAYMENT` → `CONFIRMED`). **O momento-chave de cada cenário vem numa faixa colorida**: vermelho = morreu/falhou (`MORREU: inventory-service-1`, `BROKER FORA DO AR`), verde = recuperou (`A OUTRA RÉPLICA ASSUMIU`, `BROKER DE VOLTA`), amarelo = réplicas/escala/contagens (`ESCALA HORIZONTAL: 3 réplicas`), roxo = compensação. Junto vem o retrato das réplicas (● rodando, ⏸ congelada, ✖ morta) |
| **T2 logs** (direita, em cima) | `scripts/demo.sh logs` | o lado assíncrono ao vivo: cada evento **publicado** e **consumido**, por qual réplica; em destaque `DUPLICADA` (F3), `REJEITADA` (F4), `BROKER FORA` / `broker de volta` (F6), `ESCALA` (autoescala, só quando muda). **Containers em faixa colorida**: `✖ MORREU (kill)`, `⏸ CONGELADO`, `▶ DESCONGELADO`, `▲ SUBIU`, `■ PAROU` |
| **T3 filas** (direita, embaixo) | `scripts/queues.sh watch` | profundidade das filas da saga a cada segundo e, à direita, as **réplicas de cada serviço** (`inventory 1+1✖` em vermelho quando uma morre, `+1⏸` em amarelo quando congela, `payment 5` na autoescala) |
| **Navegador** | Jaeger (trace do último pedido), pgAdmin, RedisInsight, SQS Admin | seção 5.2 |
| **Editor** | arquivos da seção 7 abertos em abas | "apontar no código" |

A narração do T1 só aparece em terminal (no `demo.sh all` fica desligada). `DEMO_TRACE=0` desliga e `DEMO_TRACE=1` força.

Réplicas, se precisar (alternativa ao T3):
```bash
while true; do clear; docker compose ps order-service inventory-service payment-service user-service --format 'table {{.Name}}\t{{.Status}}'; sleep 1; done
```

---

## 1. Sequência da demo (ordem de execução)

| # | O quê | Comando (em T1) | Requisito | Tempo |
|---|---|---|---|---|
| 1 | A stack de pé | `docker compose ps` + `scripts/check-ports.sh` + `scripts/queues.sh` + `scripts/queues.sh subs` | contêineres, health, 1 porta, filas/DLQs, fan-out | 1 min |
| 2 | Contratos | `make -C contracts check` | envelope + AsyncAPI | 20 s |
| 3 | Caminho feliz | `scripts/demo.sh happy` | **R3** + bônus OTel + CQRS | 2 min |
| 4 | Réplicas na mesma fila | `scripts/demo.sh replicas` | **R4** | 1 min |
| 5 | **O professor escolhe a falha** | `scripts/demo.sh` → `f1`…`f8` | F1–F8 | 1–2 min |
| 6 | **Falha surpresa** | `scripts/demo.sh surprise <serviço> [s]` | — | 1–2 min |
| 7 | F5 (se não foi escolhido) | `scripts/demo.sh f5` | **R2** | 1 min |
| 8 | Bônus restantes | `versioning`, `autoscale`, IaC | bônus | 3 min |

Com pouco tempo: 3 → 5 → 6 → 8 (versionamento + autoescala). O caminho feliz já cobre OTel e CQRS.

---

## 2. Passo a passo com "onde mostrar"

### 1) A stack de pé
- **Rodar:** `docker compose ps` → todos `healthy`; ~25 contêineres; order/inventory/payment/user com **2 réplicas**.
- `scripts/check-ports.sh` → só o gateway (e o Jaeger, ferramenta de operação) publica porta.
- `scripts/verify-infra.sh | tail -5` → tópicos SNS, filas SQS + DLQ, bucket S3, segredo no Secrets Manager.
- `scripts/queues.sh` → as 7 filas e as 7 DLQs com a profundidade; `scripts/queues.sh subs` → cada tópico entregando para várias filas, com filtro por `type`.
- **Falar:** 8 serviços, 3 linguagens (Go, Java, C#), banco por serviço, tudo com `docker compose up`, AWS emulada pelo MiniStack.

### 2) Contratos
- **Rodar:** `make -C contracts check`.
- **Mostrar:** a pasta `contracts/` (JSON Schema por evento + `asyncapi.yaml`) e o envelope `messageId, correlationId, type, version, occurredAt, payload`.

### 3) Caminho feliz — `scripts/demo.sh happy`
**Previsão (o script mostra):** 202 com id → polling até `CONFIRMED` → ingressos em `/me/tickets` → comprovante no S3 → mesma `Idempotency-Key` devolve o mesmo pedido.

**Onde mostrar o resultado:**
| Evidência | Onde aparece |
|---|---|
| **202 + id, depois status** (R3) | saída do `happy-path.sh` em T1 (linhas `ok`) |
| Estado da saga + outbox | seção *orders-db* da evidência (`CONFIRMED`, eventos publicados) |
| Reserva e ingressos | seção *inventory-db* (`CONFIRMED`, 2 ingressos) |
| Cobrança | seção *payments-db* (`APPROVED`, cobrado) |
| Comprovante + read model | seção *notifications-db / users-db* (`em_my_tickets = 2`) |
| Logs em ordem, por réplica | seção *logs do pedido*; ou `scripts/queues.sh trail` (quem publicou → qual réplica consumiu) |
| **Trace** | link `Trace no Jaeger: http://localhost:16686/trace/...` no fim → abrir no navegador (seção 6.1) |
| Comprovante no S3 (opcional) | comando da seção 5 |

**Falar:** "o POST responde 202 na hora; o resto é assíncrono, por eventos. O cliente consulta o status depois." `TicketsIssued` tem **2 consumidores** (user e notification): é o *evento com vários interessados* do enunciado.

### 4) R4 — `scripts/demo.sh replicas`
- **Mostrar:** a saída conta `message_consumed` **por réplica** de order, inventory e payment; todas consumiram e não houve efeito duplicado.
- **Falar sobre ordenação (o enunciado pede):** fila SQS padrão não garante ordem, e **não dependemos dela**. Toda transição é `UPDATE ... WHERE id = ? AND status = <esperado>`: evento fora de ordem ou duplicado afeta 0 linhas e é logado como `stale_event`. Não usamos FIFO porque limitaria o paralelismo entre réplicas e a idempotência já resolve.

### 5) O professor escolhe — `scripts/demo.sh` → digite `f1`…`f8`
Pergunte: *"Qual falha o senhor quer ver?"* Detalhe de cada uma na seção 3.

### 6) Falha surpresa — `scripts/demo.sh surprise <serviço> [segundos]`
"Pode escolher qualquer serviço." O script mostra a previsão, derruba **todas as réplicas**, compra durante a queda, sobe de novo e mostra a evidência. Tabela de previsões na seção 4.

### 7) F5 — `scripts/demo.sh f5`
O resultado mais visual da corretude: **10 CONFIRMED, 90 OUT_OF_STOCK, available = 0**.

### 8) Bônus — seção 6.

---

## 3. F1–F8: comando, previsão e onde está a prova

| # | `demo.sh` | Tempo | Previsão (ler antes do Enter) | Onde mostrar a prova |
|---|---|---|---|---|
| **F1** Compensação | `f1` | ~31 s | Pagamento 100% falha → `PaymentFailed` → pedido `CANCELLED(PAYMENT_FAILED)` → reserva `RELEASED`, estoque devolvido → notification registra `ORDER_CANCELLED` | *orders-db*: `CANCELLED / PAYMENT_FAILED`; *inventory-db*: reserva `RELEASED` e `disponivel_no_setor` igual ao inicial; *payments-db*: `FAILED / DECLINED`; *notifications-db*: `ORDER_CANCELLED` (cliente notificado); T2: `compensation_applied` |
| **F2** Serviço morre no meio | `f2` | ~33 s | Réplica do inventory congelada segurando mensagens; `docker kill` nela; o que não teve commit não teve ack → outra réplica processa depois do visibility timeout; 1 reserva por pedido | T1: `N message(s) were in flight on the killed replica` + 20 reservas, nenhuma dupla; T3: réplica sumindo e voltando; evidência: *processed_messages* |
| **F3** Duplicada ×5 | `f3` | ~4 s | Mesmo `messageId` 5× → 1 reserva, 1 linha em `processed_messages`, 4 entregas `duplicate=true` | **T2: 4 linhas `duplicate=true`**; evidência: *inventory-db* 1 reserva; *processed_messages* |
| **F4** Venenosa | `f4` | ~2 s | Payload inválido é recebido N vezes (`maxReceiveCount=3`) e vai para a DLQ; a válida logo depois é processada | T2: `message_rejected`; evidência: seção *DLQ do inventory* com 1; depois `scripts/dlq.sh show inventory-service` mostra a mensagem |
| **F5** Corrida 100/10 | `f5` | ~34 s | 10 `CONFIRMED`, 90 `CANCELLED(OUT_OF_STOCK)`, `available = 0`, nunca negativo | evidência: contagem por status no *orders-db*; setor F5 `available 0` no *inventory-db*; abrir `ReservationRepository.java:46` (UPDATE condicional) e `V0004__create_catalog.sql:19` (CHECK) |
| **F6** Broker fora 30 s | `f6` | ~66 s | `docker pause ministack`; POST continua 202, pedido fica `PENDING`, evento espera no outbox; ao despausar, reconecta sozinho, outbox drena, saga termina | **T2: `broker_down` … `broker_up`**; evidência: pedido final `CONFIRMED`; *outbox pendentes = 0*. Enquanto espera, explicar o outbox |
| **F7** Resposta tardia | `f7` | ~44 s | Latência 20 s > prazo 15 s → `CANCELLED(PAYMENT_TIMEOUT)`, estoque volta; aprovação atrasada vira `RefundRequested` → `PaymentRefunded` | *orders-db*: `CANCELLED / PAYMENT_TIMEOUT / refund_status REFUNDED`; *payments-db*: `APPROVED`, cobrado, **estornado = t**; *inventory-db*: `RELEASED`; T2: `late_payment_refund` |
| **F8** Escala ×3 | `f8` | ~12 s | `--scale payment-service=3`, 12 pedidos; as 3 dividem a fila; 1 pagamento e ≤1 cobrança por pedido | **T3: 3 réplicas do payment**; evidência: `pagamentos = pedidos`, `cobrados_2x = 0` |

Se sobrar dúvida em qualquer pedido: `scripts/demo.sh order <orderId>` mostra esse pedido em todos os bancos, logs e trace.

---

## 4. Falha surpresa: o que prever para cada serviço

| Serviço | Comando | Previsão (dizer antes) |
|---|---|---|
| inventory | `surprise inventory-service` | 202, pedido fica `PENDING` (o `OrderCreated` espera na fila); ao voltar, reserva e termina `CONFIRMED` |
| payment (curto) | `surprise payment-service` | `AWAITING_PAYMENT`; volta antes de 15 s → confirma |
| payment (longo) | `surprise payment-service 25` | passa do prazo → `CANCELLED(PAYMENT_TIMEOUT)`, estoque volta, aprovação atrasada vira estorno |
| order | `surprise order-service` | `/orders` dá 502 (as duas réplicas fora); respostas esperam na fila; nada se perde |
| auth | `surprise auth-service` | cadastro/login dão 502, **mas quem tem token compra** (gateway valida JWT local, JWKS em cache) |
| notification | `surprise notification-service` | compra confirma; comprovante no S3 aparece quando volta |
| user | `surprise user-service` | `/me*` 502; ao voltar, a projeção (CQRS) alcança |
| gateway | `surprise gateway` | cliente recebe conexão recusada (única porta pública); sagas em andamento seguem |
| banco (`orders-db` etc.) | `surprise orders-db` | escritas falham, `ready` 503, sem ack → mensagens voltam e são processadas 1 vez |
| MiniStack | `surprise ministack` | roda o F6 (matar apagaria as filas em memória; pausar é o cenário do enunciado) |

Se o serviço não voltar: `docker compose up -d && scripts/wait-healthy.sh`.

---

## 5. Comandos avulsos de evidência (se pedirem "mostra no banco")

Pedido em todos os bancos (padrão: o último):
```bash
scripts/demo.sh order
```

Abrir um banco direto (troque `orders` por `inventory`, `payments`, `notifications`, `users`, `auth`):
```bash
docker compose exec orders-db psql -U orders_owner -d orders -c "SELECT id, status, cancel_reason, refund_status FROM orders ORDER BY created_at DESC LIMIT 5"
```

Idempotência (R1):
```bash
docker compose exec inventory-db psql -U inventory_owner -d inventory -c "SELECT consumer, count(*) FROM processed_messages GROUP BY 1"
```

DLQs:
```bash
scripts/dlq.sh list
```

```bash
scripts/dlq.sh show inventory-service
```

Comprovantes no S3:
```bash
bash -c '. scripts/lib/aws.sh; load_env; awsl s3 ls s3://ticketpro-receipts/receipts/ | tail -3'
```

Mudar a dependência instável ao vivo (R5), se pedirem:
```bash
PAYMENT_FAILURE_RATE=1 docker compose up -d --no-deps payment-service
```

```bash
PAYMENT_LATENCY_MS=20000 docker compose up -d --no-deps payment-service
```

Voltar ao normal:
```bash
docker compose up -d --no-deps payment-service
```

### 5.0 Collection Postman pelo menu — `c` no `scripts/demo.sh`

A opção **`c`** do menu navega pela collection (`docs/postman/`): pastas, subpastas e cada uma das 98 requests. Escolha um número para entrar numa pasta ou rodar uma request; **`0`** roda tudo daquele nível; `v` volta. O script sempre roda o **00 · Setup** antes (cria os usuários A e B, o token e descobre o evento do seed) e usa o Newman, que mostra cada request com o status HTTP e cada teste ✓/✗. Atalho para uma pasta: `scripts/demo.sh collection 03` (Pedidos), `collection 01` (Auth) etc.

- Pré-requisito, uma vez só: `npm install -g newman@6` (já instalado nesta máquina; o menu avisa se faltar).
- **Itens ⏳** (refresh e logout, não implementados no auth) são **pulados** ao rodar uma pasta e o menu diz quantos; escolhidos diretamente, rodam (e dão 404).
- **05 · F1–F8** e **07 · Checagens**: escolher um item **roda o cenário real** (`f1`…`f8`, `payment`, `notification`, `gateway`), com banners e evidência, porque a falha precisa de docker.
- Request avulsa que depende de outra da mesma pasta (ex.: `Login` precisa do `Register`; `GET /orders/{id}` do POST): rode a pasta (`0`).
- Testado em 25/11: Setup 8/8, Auth 25/25, Catálogo 17/17, Pedidos 36/36, Usuário 19/19, Bônus 12/12, Checagens 11/11; **collection inteira (`0` na raiz): 69 requests, 77/77 asserções**.

### 5.1 Filas ao vivo (SNS/SQS) — `scripts/queues.sh`

| Comando | Mostra | Quando usar |
|---|---|---|
| `scripts/queues.sh` | profundidade de todas as filas e DLQs (visíveis / em voo), ~5 s | passo 1 (stack de pé) e depois do F4 / versionamento (DLQ do inventory sobe) |
| `scripts/queues.sh subs` | tópico SNS → fila SQS com o **filtro por `type`** | slide/explicação de mensageria: fan-out e o evento com vários interessados (`TicketsIssued` → user + notification) |
| `scripts/queues.sh trail [orderId]` | cada evento do pedido: **quem publicou e qual réplica consumiu**, em ordem (padrão: último pedido) | depois do caminho feliz (R4: réplicas revezando) e de qualquer F com pedido |
| `scripts/queues.sh watch` | uma linha por segundo com as filas da saga (`fila=visíveis/em_voo`), Ctrl-C para | em T3, durante a falha surpresa, F2 e autoescala |

Por que a fila fica em 0 no caminho feliz: os consumidores fazem long polling e consomem em milissegundos (a saga inteira leva ~1 s). Para **ver a fila encher**, derrube o consumidor com o `watch` aberto em T3:

```bash
scripts/demo.sh surprise inventory-service
```

O que aparece no `watch`: `inventory=0/1` ou `1/0` enquanto ele está fora (o `OrderCreated` espera na fila, o pedido fica `PENDING`) e `0/0` logo depois que ele volta. `em_voo` = uma réplica recebeu e ainda não deu ack; se ela morrer, a mensagem volta depois do visibility timeout de 30 s (é o F2).

Exemplo real de `trail` (pedido feito com o inventory pausado por 5 s):

```
19:19:40.470  order-service-2         publicou   OrderCreated v2
19:19:45.872  inventory-service-1     consumiu   OrderCreated v2      ← esperou na fila durante a pausa
19:19:46.076  inventory-service-1     publicou   InventoryReserved v1
19:19:46.085  order-service-2         consumiu   InventoryReserved v1
19:19:46.146  order-service-2         publicou   PaymentRequested v1
19:19:46.203  payment-service-1       consumiu   PaymentRequested v1
19:19:46.825  payment-service-2       publicou   PaymentApproved v1
19:19:46.922  order-service-1         consumiu   PaymentApproved v1
19:19:46.961  order-service-2         publicou   OrderConfirmed v1
19:19:46.989  inventory-service-2     consumiu   OrderConfirmed v1
19:19:47.011  user-service-2          consumiu   OrderConfirmed v1
19:19:47.087  inventory-service-2     publicou   TicketsIssued v1
19:19:47.110  user-service-1          consumiu   TicketsIssued v1
19:19:47.185  notification-service-1  consumiu   TicketsIssued v1
```

### 5.2 Ver tudo visualmente (pgAdmin, RedisInsight, Jaeger) — `scripts/tools.sh`

`scripts/tools.sh up` sobe três ferramentas **fora do projeto compose**: não fazem parte do sistema, o `check-ports.sh` continua verde e as portas ficam só em `127.0.0.1`. `scripts/tools.sh urls` lista o que está no ar; `scripts/tools.sh down` remove.

| Ferramenta | URL | O que mostra |
|---|---|---|
| **pgAdmin** | http://localhost:5050 | os 6 bancos (grupo **Ticket Pro**), já cadastrados e **sem senha** (credenciais de dev lidas de `services/*/db.env.example`) |
| **RedisInsight** | http://localhost:5540 | o `gateway-redis`, já cadastrado. **Na primeira vez**, aceite a EULA (marque "I have read and understood the Terms") |
| **Jaeger** | http://localhost:16686 | traces (já faz parte da stack) |
| **SQS Admin** | http://localhost:3999 | as 14 filas do MiniStack e, **nas DLQs**, as mensagens com o envelope completo (`messageId`, `correlationId`, `type`, `version`, `occurredAt`, `payload`) |
| Filas vivas | terminal | `scripts/queues.sh watch` / `trail` (seção 5.1) |

> ⚠️ **SQS Admin: clique SÓ em fila `-dlq`.** A tela consulta a fila selecionada a cada ~3 s, e cada consulta conta como uma entrega. Numa fila viva (`maxReceiveCount=3`), uma mensagem esperando vai para a DLQ em ~15 s. Testado em 25/11 numa fila descartável. Se isso acontecer durante a falha surpresa, o `OrderCreated` vai para a DLQ e a saga trava. Nunca use **PURGE QUEUE** nem **DELETE QUEUE**. Deixe a aba já com a `ticketpro-inventory-service-dlq` selecionada.
>
> Melhor momento para usar: **depois do F4 ou do versionamento**. Abra a `ticketpro-inventory-service-dlq` e mostre a mensagem venenosa (`quantity:"two"`) ou a `OrderCreated` v3, com o envelope.

**Como mostrar no pgAdmin:** Ticket Pro → `orders (order-service)` → Databases → `orders` → Schemas → public → Tables → botão direito em `orders` → **View/Edit Data → All Rows**. Para consultas, selecione o banco e abra o **Query Tool** (ícone de raio ou Alt+Shift+Q). Consultas boas para a banca:

| Banco | Consulta | Prova |
|---|---|---|
| orders | `SELECT id, status, cancel_reason, refund_status, updated_at FROM orders ORDER BY created_at DESC LIMIT 10;` | estados da saga |
| orders | `SELECT type, published_at IS NOT NULL AS publicado, attempts FROM outbox ORDER BY id DESC LIMIT 10;` | outbox (R6) |
| inventory | `SELECT name, capacity, available FROM sectors;` | estoque, nunca negativo (R2) |
| inventory | `SELECT order_id, status, quantity FROM reservations ORDER BY created_at DESC LIMIT 10;` | reservas e compensação |
| inventory | `SELECT consumer, count(*) FROM processed_messages GROUP BY 1;` | idempotência (R1) |
| payments | `SELECT order_id, status, failure_reason FROM payments ORDER BY created_at DESC LIMIT 10;` | dependência instável (R5) |
| users | `SELECT user_id, order_id, ticket_id, issued_at FROM my_tickets ORDER BY issued_at DESC LIMIT 10;` | read model CQRS |

O pgAdmin é o único lugar que enxerga todos os bancos (está ligado às redes `<svc>-data`): é ferramenta de operação, como o Jaeger. Os serviços continuam isolados, cada um só resolve o próprio banco (ADR-0004). Vale dizer isso se o professor perguntar.

**O que tem no Redis:** só estado efêmero do gateway: `revoked:<jti>` (blacklist de tokens revogados, com TTL até o token expirar) e `ratelimit:<chave>` (buckets do rate limit, somem em segundos). Com a stack parada ele fica vazio. Para aparecer chave ao vivo, rode `scripts/demo.sh gateway` e atualize o Browser do RedisInsight: surge o `revoked:<jti>` com TTL.

---

### 5.3 Glossário dos logs (o que aparece no T2 e nas evidências)

Todo log é JSON com `timestamp`, `service`, `message` (o tipo abaixo), `correlationId`, `traceId` e, quando é sobre uma mensagem, `messageId`, `type` e `version`.

| Log (`message`) | Quem | O que significa | No T2 |
|---|---|---|---|
| `message_published` | relay do outbox (todos) | o evento saiu do outbox para o SNS, depois do commit | `publicou X` |
| `message_consumed` | consumidor (todos) | processou a mensagem e só então deu ack; com `duplicate:true`, já estava em `processed_messages` e foi ignorada (R1) | `consumiu X` / `DUPLICADA` |
| `message_rejected` | consumidor | payload inválido ou versão desconhecida: não dá ack; após 3 recebimentos vai para a DLQ (F4, v3) | `REJEITADA` |
| `stale_event` | order (saga) | evento que chegou fora de ordem, repetido ou para pedido inexistente: o `UPDATE ... WHERE status = <esperado>` afetou 0 linhas, nada regride | `fora de ordem/atrasado` |
| `compensation_applied` | order | a saga cancelou o pedido e publicou `OrderCancelled`. `reason`: `OUT_OF_STOCK` (InventoryRejected, F5), `PAYMENT_FAILED` (PaymentFailed, F1), `PAYMENT_TIMEOUT` (prazo estourou, F7) | `COMPENSAÇÃO` |
| `stock_released` | inventory | consumiu `OrderCancelled` e devolveu a reserva ao estoque | `ESTOQUE DEVOLVIDO` |
| `late_payment_refund` | order / payment | aprovação chegou depois do cancelamento: order pede `RefundRequested`, payment estorna (F7) | `APROVAÇÃO ATRASADA` |
| `stock_reserved` / `stock_rejected` | inventory | `OrderCreated` reservou / não havia saldo (F5: 90 rejeitados) | — |
| `tickets_issued` | inventory | consumiu `OrderConfirmed` e emitiu os ingressos (`TicketsIssued`) | — |
| `payment_accepted` / `payment_completed` | payment | gravou `payments(PENDING)` e deu ack / o worker chamou o provedor e terminou | — |
| `order_projected` / `tickets_projected` / `profile_created` | user | read model CQRS atualizado (`my_orders`, `my_tickets`, perfil) | — |
| `broker_down` / `broker_up` | relays e consumidores | perderam / recuperaram o SNS/SQS; reconectam sozinhos (F6) | `BROKER FORA` / `broker de volta` |
| `outbox_relay_error` | relay | uma tentativa de publicar falhou (ex.: broker fora); tenta de novo com backoff | — |
| `scale_decision` | autoscaler | a cada 5 s: profundidade da fila, réplicas atuais e desejadas | `ESCALA` (só quando muda) |
| `rate_limited` / `token_rejected` | gateway | 429 do rate limit / 401 por token inválido ou revogado | — |

## 6. Pontos bônus (até +1,0; cada um 0,2, **só conta se demonstrado**)

| Bônus | Como demonstrar | Onde mostrar | Frase-chave |
|---|---|---|---|
| **6.1 Tracing (OpenTelemetry)** | link do Jaeger impresso no fim do `demo.sh happy` (ou `demo.sh order`) | Navegador → trace → spans de **gateway (Go) → order (Java) → inventory (Java) → payment (C#) → notification (Go) / user (C#)** | "O `traceparent` viaja no header HTTP e nos atributos da mensagem SNS/SQS: um trace só, três runtimes." |
| **6.2 IaC (Terraform)** | `docker compose logs infra-init \| grep -E "Apply complete"` + `scripts/verify-infra.sh \| tail -3` | T1 + abrir `infra/terraform/main.tf` e `modules/{messaging,storage,secrets}` | "Nada é criado à mão. O `infra-init` roda `terraform apply` a cada `up`; o `down -v` de hoje recriou tudo do zero: `Apply complete! Resources: 40 added`." |
| **6.3 Autoescala por fila** | `scripts/demo.sh autoscale` (~40–90 s) | **T3: payment subindo de 2 para até 5 e voltando a 2**; T2: `scale_decision depth=… desired=…` | "O autoscaler lê a profundidade da fila e ajusta as réplicas pela API do Docker." |
| **6.4 CQRS** | já no `happy`: `/me/tickets` + `em_my_tickets = 2`; ou o SQL abaixo | *users-db* `my_tickets` | "O user-service tem um modelo de leitura próprio, montado só por eventos (`TicketsIssued`). Ninguém lê o banco do inventory." |
| **6.5 Versionamento de eventos** | `scripts/demo.sh versioning` | T1: v1 convertida (`channel=API`), v2 processada, v3 na DLQ; depois `scripts/dlq.sh show inventory-service`; abrir `Upcaster.java` | "v1 e v2 convivem: o consumidor converte a v1 (upcaster). Uma v3 desconhecida vai para a DLQ em vez de derrubar o serviço." |

CQRS direto no banco:
```bash
docker compose exec users-db psql -U users_owner -d users -c "SELECT user_id, order_id, ticket_id, issued_at FROM my_tickets ORDER BY issued_at DESC LIMIT 5"
```

IaC mais forte, só se sobrar tempo e no fim (reinicia o MiniStack, perde SNS/SQS e o Terraform recria; não rode antes de outro cenário):
```bash
scripts/check-infra-rerun.sh
```

**Extra (não é bônus do enunciado, mas impressiona):** `scripts/demo.sh gateway` → token revogado pelo evento `TokenRevoked` passa a dar 401; rajada de logins dá 429 com `Retry-After`.

---

## 7. "Aponte no código" (abra antes, em abas)

| Requisito | Arquivo |
|---|---|
| R1 idempotência (Java) | `services/inventory-service/src/main/java/br/furb/ticketpro/inventory/platform/idempotency/ProcessedMessages.java:18` |
| R1 idempotência (C# / Go) | `shared/dotnet/src/Idempotency/ProcessedMessages.cs`, `shared/go/idempotency/idempotency.go` |
| Ack só depois do commit | `services/inventory-service/src/main/java/br/furb/ticketpro/inventory/platform/messaging/SqsConsumer.java:259` |
| R2 zero venda dupla | `services/inventory-service/src/main/java/br/furb/ticketpro/inventory/modules/reservations/infrastructure/ReservationRepository.java:46` + `services/inventory-service/migrations/V0004__create_catalog.sql:19` |
| R3 202 Accepted | `services/order-service/src/main/java/br/furb/ticketpro/order/modules/orders/web/OrderController.java:64` |
| Saga: transição condicional | `services/order-service/src/main/java/br/furb/ticketpro/order/modules/orders/domain/Transitions.java:17` |
| Prazo do pagamento (F7) | `services/order-service/src/main/java/br/furb/ticketpro/order/modules/orders/application/DeadlineScanner.java` |
| R4 SKIP LOCKED (worker/relay) | `services/payment-service/src/PaymentService/Modules/Payments/Workers/PaymentWorker.cs`, `shared/go/outbox/relay.go` |
| R5 latência e falha por env | `services/payment-service/src/PaymentService/App/AppConfig.cs:80` + `Modules/Payments/Provider/SimulatedPaymentProvider.cs` |
| R6 reconexão / outbox | `services/order-service/src/main/java/br/furb/ticketpro/order/platform/outbox/OutboxRelay.java:230`, `shared/go/outbox/relay.go:212` |
| Autoescala | `services/autoscaler/internal/scaler/loop.go:87` |
| Versionamento | `services/inventory-service/src/main/java/br/furb/ticketpro/inventory/modules/reservations/domain/Upcaster.java` |
| Estorno (tardio) | `services/payment-service/src/PaymentService/Modules/Payments/Consumers/RefundRequestedHandler.cs` |

---

## 8. O que o enunciado avalia além da demo (ter a resposta pronta)

- **Os 3 elementos do tema:** transação com compensação = saga de compra (F1/F7); dependência instável = provedor de pagamento simulado (R5); evento com vários interessados = `TicketsIssued` (user + notification), também `OrderConfirmed`/`OrderCancelled`.
- **Justificativa de cada serviço de nuvem:**
  - **SNS + SQS** (e não RabbitMQ/Kafka): fan-out nativo (1 tópico → N filas com filter policy), DLQ e redrive gerenciados, visibility timeout dá a reentrega at-least-once de graça; é gerenciado, então conta como categoria de nuvem. Kafka seria excesso (ordem por partição e retenção não precisamos).
  - **S3** (comprovantes): objeto imutável, barato, acessível por URL; não faz sentido guardar arquivo no Postgres.
  - **Secrets Manager** (chave do JWT): segredo fora do código e do `.env`, com rotação; SSM Parameter Store serviria, mas Secrets Manager é feito para segredo.
  - **MiniStack** (e não nuvem pública): custo zero, reproduzível na máquina de qualquer um, mesmo código (só `AWS_ENDPOINT_URL` muda); validamos os recursos com spikes (ADR-0009).
- **Ordenação (R4):** ver passo 4 da seção 2.
- **Matriz requisito → evidência:** `docs/arquitetura.md` §11 (slide 25).

---

## 9. Plano B

| Problema | Saída |
|---|---|
| Compra logo depois de F1/F5/F7 | Esses cenários recriam o payment. O long poll das réplicas antigas fica aberto no MiniStack por até 10 s e pode "engolir" uma mensagem por 30 s, passando do prazo de 15 s. Os scripts já esperam 10 s depois de recriar (`set_payment`); se cancelar mesmo assim, é a compensação funcionando |
| Compra cancelou sem querer (payment falha 10% por padrão) | Virar demo: "olha a compensação" → `scripts/demo.sh order` |
| Serviço não voltou depois da surpresa | `docker compose up -d && scripts/wait-healthy.sh` |
| Script interrompido no meio (env ou réplicas alteradas) | `docker compose up -d` volta ao compose |
| 429 logo depois de outro script | esperar ~6 s (rate limit de login 1/s) |
| Filas sumiram (MiniStack reiniciado) | `docker compose up -d --force-recreate infra-init` |
| Stack não sobe | vídeo gravado no ensaio + prints (`demo.sh all` 13/13, Jaeger, F5) |
