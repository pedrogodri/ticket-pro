# Runbook: demonstração do Ticket Pro

Roteiro da apresentação e do ensaio (roadmap §F5). Para cada item há:
- o comando;
- o que observar (saída, log, Jaeger ou query);
- o resultado esperado.

Todo script termina com `<nome>: 0 failed` e exit 0 quando passa, e devolve a stack ao estado em que a encontrou.

## Antes de começar

**Pré-requisitos:**
- Docker (OrbStack ou Docker Desktop) com ≥ 6 GB de memória;
- `curl`, `jq` e `uuidgen`;
- portas 8080 (gateway) e 16686 (Jaeger) livres.

**Preparo**, uns 5 minutos na primeira vez e uns 2 com as imagens em cache:

```bash
docker compose down -v
docker compose up -d --build
scripts/wait-healthy.sh 300
scripts/verify-infra.sh | tail -1     # all checks passed
```

Deixe abertos:
- o Jaeger, em http://localhost:16686;
- um terminal com `docker compose logs -f inventory-service order-service payment-service | grep -E 'message_consumed|scale_decision|message_rejected'`.

## Roteiro

Os tempos foram medidos no ensaio de 18/11, com a stack já no ar: uns 6 minutos para todos os scripts. Some a explicação de cada item e o preparo.

| # | Item | Comando | Tempo |
|---|---|---|---|
| 0 | Caminho feliz (R3) | `scripts/happy-path.sh` | ~5 s |
| 1 | Réplicas na mesma fila (R4) | `scripts/check-replicas.sh 3` | ~15 s |
| 2 | F1 compensação | `scripts/f1-compensation.sh` | ~30 s |
| 3 | F2 kill de consumidor | `scripts/f2-consumer-kill.sh` | ~30 s |
| 4 | F3 duplicata ×5 (R1) | `scripts/f3-duplicate.sh` | ~5 s |
| 5 | F4 mensagem venenosa | `scripts/f4-poison.sh` | ~5 s |
| 6 | F5 100 pedidos / 10 ingressos (R2) | `scripts/f5-race.sh` | ~45 s |
| 7 | F6 broker fora 30 s (R6) | `scripts/f6-broker-down.sh` | ~40 s |
| 8 | F7 resposta tardia (R5) | `scripts/f7-late-response.sh` | ~75 s |
| 9 | F8 escala para 3 réplicas | `scripts/f8-scale.sh` | ~15 s |
| 10 | Bônus: gateway, revogação e rate limit | `scripts/gateway-check.sh` | ~10 s |
| 11 | Bônus: versionamento | `scripts/demo-versioning.sh` | ~5 s |
| 12 | Bônus: autoescala | `scripts/demo-autoscale.sh` | ~40–90 s |
| 13 | Bônus: OpenTelemetry, CQRS e IaC | Jaeger, `/me/tickets` e Terraform (abaixo) | ~2 min |

Para rodar tudo de uma vez, por exemplo no ensaio:

```bash
for f in scripts/f[1-8]-*.sh; do $f | tail -1; done
```

### 0. Caminho feliz (R3)

`scripts/happy-path.sh`

O script percorre a compra inteira, só pelo gateway:
1. cadastro e login;
2. catálogo;
3. `POST /orders` com `202` + `Location`;
4. polling até `CONFIRMED`;
5. os 2 ingressos em `/me/tickets`;
6. o comprovante `receipts/{orderId}.json` no S3;
7. a mesma `Idempotency-Key` devolve `200` com o mesmo pedido.

**Observar:** no Jaeger, o serviço `inventory-service` mostra `process OrderConfirmed` → `publish TicketsIssued` e, no mesmo trace, `notification-service` com `process TicketsIssued`.

**Esperado:** `happy-path: 0 failed`.

### 1. Réplicas (R4)

`scripts/check-replicas.sh 3`

Roda o caminho feliz 3 vezes e conta `message_consumed` por réplica de order, inventory e payment.

**Esperado:** cada uma das 6 réplicas consumiu pelo menos uma mensagem.

### 2. F1: compensação

`scripts/f1-compensation.sh`

Recria o payment com `PAYMENT_FAILURE_RATE=1` ([como o payment funciona](demo/payment-service.md)). O pedido segue `PaymentFailed` → `OrderCancelled(PAYMENT_FAILED)`, e o inventory devolve o estoque.

**Esperado:**
- `CANCELLED(PAYMENT_FAILED)`;
- `available` antes, durante e depois, voltando ao valor inicial;
- a reserva em `RELEASED`.

### 3. F2: kill de consumidor

`scripts/f2-consumer-kill.sh`

1. Pausa uma réplica do inventory: ela fica segurando mensagens em voo.
2. Publica 20 `OrderCreated`.
3. Mata essa réplica.

Nada do que ela recebeu foi commitado nem teve ack. Depois do visibility timeout, a outra réplica processa essas mensagens.

**Esperado:** `N message(s) were in flight on the killed replica`, as 20 reservas criadas e nenhuma em dobro.

### 4. F3: duplicata ×5 (R1)

`scripts/f3-duplicate.sh`

Publica o mesmo envelope (mesmo `messageId`) 5 vezes.

**Observar:** no terminal de logs, 4 linhas com `"duplicate":true`.

**Esperado:** 1 reserva e 1 linha em `processed_messages`.

### 5. F4: mensagem venenosa

`scripts/f4-poison.sh`

Publica um `OrderCreated` com payload inválido. Ele é rejeitado (`message_rejected`) e vai para `ticketpro-inventory-service-dlq`. A mensagem válida publicada logo depois é processada normalmente.

**Mostrar a DLQ:**
- `scripts/dlq.sh list` para as contagens;
- `scripts/dlq.sh show inventory-service` para ver as mensagens;
- `scripts/dlq.sh redrive inventory-service` para devolvê-las à fila de origem.

### 6. F5: 100 pedidos para 10 ingressos (R2)

`scripts/f5-race.sh`

1. Repõe o setor `F5` com 10 unidades.
2. Cria 20 usuários.
3. Dispara 100 `POST /orders` concorrentes (5 por usuário, dentro do rate limit).

O pagamento roda com taxa de falha 0, para nenhuma unidade voltar ao estoque.

**Esperado:** 10 `CONFIRMED`, 90 `CANCELLED(OUT_OF_STOCK)` e `available = 0`, nunca negativo.

### 7. F6: broker fora (R6)

`scripts/f6-broker-down.sh`

Pausa o MiniStack por 30 s. Durante a pausa:
- o `POST /orders` continua respondendo `202`;
- o pedido fica em `PENDING`;
- o outbox acumula.

Depois do `unpause`, os relays drenam com backoff e a saga chega a um estado final sem intervenção.

**Observar:** logs `broker_down` / `broker_up` nos serviços.

**Esperado:** estado final `CONFIRMED`, ou `CANCELLED` se o pagamento passar do deadline, e outbox vazio.

### 8. F7: resposta tardia (R5)

`scripts/f7-late-response.sh`

O payment sobe com latência de 20 s, maior que o deadline de 15 s do pedido ([como o payment funciona](demo/payment-service.md)). Em sequência:
1. o deadline scanner cancela o pedido com `PAYMENT_TIMEOUT` e o inventory devolve o estoque;
2. a aprovação chega atrasada e vira `RefundRequested`;
3. o payment estorna e publica `PaymentRefunded`;
4. o order marca `refund_status = REFUNDED`.

**Esperado:** as 3 etapas `ok`.

### 9. F8: escala

`scripts/f8-scale.sh`

`--scale payment-service=3`, com o autoscaler parado durante o teste, e 12 pedidos.

**Esperado:**
- 3 réplicas;
- os 12 pedidos com estado final;
- um pagamento por pedido, nenhum cobrado duas vezes;
- pelo menos 2 réplicas consumindo;
- no fim, a escala e o autoscaler voltam ao estado anterior.

### 10–13. Bônus

| Bônus | Como mostrar | Esperado |
|---|---|---|
| **Gateway** (RFC 0002) | `scripts/gateway-check.sh` | token revogado por `TokenRevoked` recebe `401`; logins repetidos recebem `429` + `Retry-After` |
| **Payment sozinho** (R5, R1; RFC 0009) | `scripts/payment-check.sh` (~2 min: recria as réplicas três vezes) | `APPROVED` com um só pagamento e uma só cobrança para 4 entregas; um estorno do valor capturado para 2 `RefundRequested`; `FAILED(DECLINED)` com taxa 1; `FAILED(PROVIDER_TIMEOUT)` sem cobrança com timeout menor que a latência |
| **Versionamento** | `scripts/demo-versioning.sh` (detalhes em [demo/versioning.md](demo/versioning.md)) | v1 é convertido para `channel = API`, v2 mantém `WEB` e v3 vai para a DLQ |
| **Autoescala** | `scripts/demo-autoscale.sh` | com 40 mensagens acumuladas, o payment sobe acima de 2 réplicas (até 5) e volta a 2 depois do cooldown; o log `scale_decision` mostra a profundidade e as réplicas |
| **OpenTelemetry** | Jaeger → serviço `gateway` → um `GET /events/{id}` | um trace com gateway, inventory e as queries (`SELECT`) |
| **CQRS** | `GET /me/tickets` (o caminho feliz já mostra) | read model do user montado só a partir de eventos |
| **IaC** | `docker compose logs infra-init \| tail -20` | o `terraform apply` criou tópicos, filas, DLQs, assinaturas, bucket e segredo |

## Problemas comuns

| Sintoma | Causa | O que fazer |
|---|---|---|
| `429` num script logo depois de outro | rate limit de login por IP (1/s, burst 5) | esperar uns 6 s; os scripts F1–F8 já refazem a requisição respeitando `Retry-After` |
| Serviço `unhealthy` ou `wait-healthy` com timeout | primeiro boot lento ou falta de memória | `docker compose ps`, `docker compose logs <svc>`; subir a memória do Docker |
| Filas sumiram depois de reiniciar o MiniStack | o MiniStack guarda SNS/SQS só em memória | `docker compose up -d --force-recreate infra-init` |
| `auth-service` reiniciando no boot | subiu antes do segredo do JWT existir | esperado: `restart: on-failure` resolve sozinho em segundos |
| Algo ficou alterado depois de um script interrompido | o `trap` não rodou | `docker compose up -d` volta às réplicas e envs do compose |
| Estado sujo entre ensaios | dados de execuções anteriores | `docker compose down -v && docker compose up -d --build` |
