# Como rodar a demo: scripts e collection

Há duas formas de exercitar o Ticket Pro com a stack no ar:

- **Scripts** (`scripts/`): cobrem tudo, inclusive as falhas que exigem `docker` (F1, F2, F6, F7, F8), e verificam o resultado sozinhos. São a forma canônica e a usada na apresentação.
- **Collection Postman** (`docs/postman/`): request a request, pelo gateway. Boa para mostrar a API, os erros e a idempotência, mas não provoca falhas de infraestrutura.

O detalhe de cada cenário (o que observar, o resultado esperado e o tempo) está no [runbook](runbook.md). Este guia é só o "como rodar".

## 1. Subir a stack

Pré-requisitos: Docker com pelo menos 6 GB de memória, `curl`, `jq` e `uuidgen`, e as portas 8080 (gateway) e 16686 (Jaeger) livres.

```bash
docker compose down -v
docker compose up -d --build
scripts/wait-healthy.sh 300
scripts/verify-infra.sh | tail -1     # all checks passed
```

A primeira subida leva uns 5 minutos; com as imagens em cache, uns 2. O `down -v` zera os bancos: use quando quiser começar do zero.

## 2. Pelos scripts

### Na apresentação: `scripts/demo.sh`

Para cada item, ele mostra **como a falha é provocada e o resultado esperado**, espera um Enter, roda o script canônico e depois mostra a **evidência nos bancos e nos logs**.

| Comando | Uso |
|---|---|
| `scripts/demo.sh` | menu interativo: digite `0`, `f1`…`f8`, `r`, `g`, `v`, `a`, `p`, `n`, `o`, `s` |
| `scripts/demo.sh f5` | roda um item direto (`happy`, `replicas`, `f1`…`f8`, `gateway`, `versioning`, `autoscale`, `payment`, `notification`) |
| `scripts/demo.sh order [ORDER_ID]` | um pedido em todos os bancos, com os logs e o link do Jaeger (padrão: o último pedido) |
| `scripts/demo.sh surprise inventory-service` | falha surpresa: derruba o serviço, compra durante a queda, sobe de novo e mostra o desfecho |
| `scripts/demo.sh all` | ensaio: tudo em sequência, uma linha por item |

`DEMO_NO_PAUSE=1` pula as pausas.

### Scripts avulsos

Todos terminam com `<nome>: 0 failed` e exit 0 quando passam, e devolvem a stack ao estado inicial.

| O quê | Comando |
|---|---|
| Caminho feliz (R3) | `scripts/happy-path.sh` |
| Réplicas na mesma fila (R4) | `scripts/check-replicas.sh 3` |
| F1 compensação | `scripts/f1-compensation.sh` |
| F2 kill de consumidor | `scripts/f2-consumer-kill.sh` |
| F3 duplicata ×5 (R1) | `scripts/f3-duplicate.sh` |
| F4 mensagem venenosa → DLQ | `scripts/f4-poison.sh` |
| F5 100 pedidos / 10 ingressos (R2) | `scripts/f5-race.sh` |
| F6 broker fora 30 s (R6) | `scripts/f6-broker-down.sh` |
| F7 resposta tardia (R5) | `scripts/f7-late-response.sh` |
| F8 escala para 3 réplicas | `scripts/f8-scale.sh` |
| Gateway: revogação e rate limit | `scripts/gateway-check.sh` |
| Payment isolado (aprovação, idempotência, estorno) | `scripts/payment-check.sh` |
| Notification isolado (comprovante no S3) | `scripts/notification-check.sh` |
| Versionamento de evento (v1, v2, v3 → DLQ) | `scripts/demo-versioning.sh` |
| Autoescala pela fila | `scripts/demo-autoscale.sh` |

Os oito cenários de falha de uma vez:

```bash
for f in scripts/f[1-8]-*.sh; do $f | tail -1; done
```

## 3. Pela collection

Arquivos em [`docs/postman/`](postman/README.md): a collection (`ticket-pro.postman_collection.json`, v2.1, importa também no Bruno e no Insomnia) e o environment (`ticket-pro.local.postman_environment.json`, com `baseUrl`, `password` e `f5SectorId`).

### No Postman

1. **Import** dos dois arquivos.
2. Selecione o environment **Ticket Pro — local**.
3. Rode primeiro a pasta **00 · Setup (rode primeiro)**: ela cria os usuários A e B e descobre o evento e o setor do seed.
4. Rode as outras pastas pelo **Run folder** (Runner) ou request a request.
5. Na pasta **03 · Pedidos**, use **Delay = 500 ms** no Runner: o gateway limita os pedidos a 2/s por usuário e responde 429 em rajada.

### No terminal (Newman)

Setup + pedidos:

```bash
npx newman run docs/postman/ticket-pro.postman_collection.json \
  -e docs/postman/ticket-pro.local.postman_environment.json \
  --folder "00 · Setup (rode primeiro)" \
  --folder "03 · Pedidos (order-service, saga)" \
  --delay-request 500
```

A collection inteira:

```bash
npx newman run docs/postman/ticket-pro.postman_collection.json \
  -e docs/postman/ticket-pro.local.postman_environment.json \
  --delay-request 500
```

### O que cada pasta cobre

| Pasta | Conteúdo |
|---|---|
| 00 · Setup | health, usuários A e B, evento e setor do seed |
| 01 · Auth | caminho feliz; 401, 409, 400, 413 e 404; token lixo; JWKS não exposto |
| 02 · Catálogo | listagem, detalhe, 404 e 401 |
| 03 · Pedidos | saga ponta a ponta (202 → estado final), idempotência, validações, BOLA e rate limit |
| 04 · Usuário | `/me`, `/me/tickets` e `/me/orders` (read model CQRS), isolamento entre usuários |
| 05 · F1–F8 | os 8 cenários; os passos de infraestrutura são manuais (comando na descrição) |
| 06 · Bônus | trace no Jaeger, autoscale, versionamento v1/v2 |
| 07 · Checagens por serviço | apontam para `payment-check.sh`, `notification-check.sh` e `gateway-check.sh` |

## 4. O que só roda pelos scripts

- **F1, F2, F6, F7 e F8** precisam de `docker`: mudar variável de ambiente, matar réplica, pausar o broker, escalar. Pela collection só dá para ver o resultado depois.
- **Payment e notification** não têm rota HTTP de negócio: só consomem e publicam eventos. Use `payment-check.sh` e `notification-check.sh`.
- **Revogação de token:** `/auth/logout` e `/auth/refresh` ainda respondem 404 (⏳ na collection). A revogação é demonstrada pelo `gateway-check.sh`, que publica o `TokenRevoked` direto.

## 5. Problemas comuns

| Sintoma | Causa e saída |
|---|---|
| 429 nos pedidos da collection | Rajada acima de 2/s por usuário. Use o delay de 500 ms. |
| Requests da collection dão 401 | A pasta 00 · Setup não rodou nesta sessão (ela grava o token na collection). Rode-a de novo; cada rodada cria usuários novos. |
| Script para em `wait-healthy` | Algum container não ficou `healthy`. Veja `docker compose ps` e `docker compose logs <serviço>`. |
| Estado estranho depois de muitas rodadas | Zere tudo com `docker compose down -v` e suba de novo (o F5 já restaura o próprio setor antes de rodar). |
