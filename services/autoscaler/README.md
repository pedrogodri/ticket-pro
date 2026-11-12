# autoscaler

Autoescala de réplicas consumidoras pela profundidade das filas SQS ([ADR-0015](../../docs/adr/0015-queue-based-autoscaler.md)), em Go. Faz parte dos bônus da F4.

**Dono:** Pedro.

**Estado:** esqueleto. Hoje só tem config validada, logs JSON e health checks. Não tem banco nem plataforma de mensageria.

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
3. **F1:** Task 7 do [plano F1](../../docs/superpowers/plans/2026-11-12-f1-platform.md). Só confirmar o esqueleto, que já existe.
4. **F4:** a lógica de escala ([roadmap](../../docs/roadmap.md)).

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
