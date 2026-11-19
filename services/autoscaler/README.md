# autoscaler

Autoescala de réplicas consumidoras pela profundidade das filas SQS ([ADR-0015](../../docs/adr/0015-queue-based-autoscaler.md)), em Go. Faz parte dos bônus da F4.

**Dono:** Pedro.

**Estado:** F4: autoescala por profundidade de fila ([ADR-0015](../../docs/adr/0015-queue-based-autoscaler.md)). Lê `ApproximateNumberOfMessages` + `NotVisible`, decide `clamp(ceil(depth / MSGS_PER_REPLICA), MIN, MAX)` com cooldown para reduzir, e cria ou remove réplicas pela Docker Engine API.

Design: [RFC 0012](../../docs/rfc/0012-design-do-autoscaler-em-go.md). Plano: [autoscaler](../../docs/superpowers/plans/2026-11-19-autoscaler.md).

## O que a demo prova

`scripts/demo-autoscale.sh` **pausa** as réplicas do payment, acumula 40 `PaymentRequested` na fila, espera o autoscaler chegar a 5 réplicas (nunca acima de `AUTOSCALER_MAX_REPLICAS`), vê a fila drenar e, passado o cooldown, a volta para 2. Isso prova o **mecanismo** (fila → decisão → Docker API → vazão), mas não o caso de uso real: o consumidor do payment só grava `payments(PENDING)` e dá ack, então em operação normal a fila fica baixa e o backlog está na tabela `payments` (RFC 0012 §5.2, divergência D2). Diga isso na apresentação. O `f8-scale.sh` **para** o autoscaler para medir o `--scale 3`.

## Responsabilidades

- Ler `ApproximateNumberOfMessages` das filas configuradas, em especial `ticketpro-payment-service`.
- Ajustar o número de réplicas do payment e do inventory pela Docker API, dentro de limites mínimo e máximo, com cooldown.
- Ser demonstrado no cenário F8, com a fila acumulada, as réplicas subindo e a fila drenando.

## API

| Endpoint | Fase | Resposta |
|---|---|---|
| `GET /health/live` | F0 ✅ | `200 {"status":"up"}` |
| `GET /health/ready` | F0 ✅ | `200 {"status":"up","checks":{}}` |

## Eventos

Nenhum. O serviço só lê atributos das filas.

## Por onde começar

1. Ler a seção *Primeiros passos* do [README da raiz](../../README.md).
2. Ler:
   - [ADR-0015](../../docs/adr/0015-queue-based-autoscaler.md);
   - [arquitetura](../../docs/arquitetura.md) §9;
   - [RFC 0003](../../docs/rfc/0003-failure-handling-strategy.md) §5.8 (F8).
3. Design e plano: [RFC 0012](../../docs/rfc/0012-design-do-autoscaler-em-go.md) e [plano](../../docs/superpowers/plans/2026-11-19-autoscaler.md).
4. Demo ao vivo (stack no ar): `scripts/demo-autoscale.sh`.

## Variáveis de ambiente

| Variável | Obrigatória | Exemplo |
|---|---|---|
| `SERVICE_NAME` | sim | `autoscaler` |
| `PORT` | sim | `8080` |
| `LOG_LEVEL` | sim | `debug` \| `info` \| `warn` \| `error` |

## Comandos

```bash
make test        # go test
make lint        # gofmt + golangci-lint (container)
make typecheck   # go vet
make run         # sobe só este serviço
```

Sem Go instalado, os alvos rodam sozinhos no container `golang:1.25`. Para forçar: `make test TOOLCHAIN=docker`. Também há um Dev Container em [`.devcontainer/`](.devcontainer/devcontainer.json).

## Autoescala

| Variável | Padrão | Uso |
|---|---|---|
| `AUTOSCALER_TARGETS` | `payment-service=ticketpro-payment-service` | `serviço=fila`, separados por vírgula |
| `AUTOSCALER_INTERVAL_S` | 5 | intervalo entre checagens |
| `AUTOSCALER_MSGS_PER_REPLICA` | 5 | mensagens por réplica |
| `AUTOSCALER_MIN_REPLICAS`, `AUTOSCALER_MAX_REPLICAS` | 2, 5 | limites |
| `AUTOSCALER_COOLDOWN_S` | 30 | tempo com o alvo abaixo do atual antes de reduzir |
| `COMPOSE_PROJECT_NAME` | `ticketpro` | labels do compose usadas para achar as réplicas |
| `DOCKER_HOST` | `unix:///var/run/docker.sock` | socket do daemon (só `unix://`) |

**Como funciona:**
- Subir é imediato. A réplica nova clona uma existente (imagem, env, healthcheck, redes com alias do serviço) e recebe as labels do compose, então aparece no `docker compose ps`.
- Descer remove primeiro as de maior número e para cada uma com 10 s de graça; mensagens em voo voltam para a fila.
- Toda decisão sai no log como `scale_decision` (`target`, `depth`, `current`, `desired`).
- O container roda como root com o socket do Docker montado. É aceitável só para a demo local (ADR-0015).
- Os clones levam as labels do compose. Um `docker compose up -d` ou `--scale` rodado depois volta o serviço para o número declarado no compose, e o autoscaler sobe de novo no próximo tick se a fila pedir. Durante a demo, não rode `compose up` no payment.
- Um clone que falha no meio é apagado, para não travar o próximo com o mesmo nome. Uma réplica que já sumiu conta como removida.
- A redução é sequencial e cada remoção espera até 10 s; nesse tempo, os outros alvos não são checados. Com um alvo só (o padrão), isso é aceitável.

**Demo:** `scripts/demo-autoscale.sh`. Ela pausa as réplicas do compose, acumula 40 `PaymentRequested`, espera subir para 5 réplicas, drenar a fila e voltar a 2.
