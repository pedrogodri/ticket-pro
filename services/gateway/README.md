# gateway

Ponto de entrada único do Ticket Pro, em Go. É o único serviço que publica porta (`8080`).

**Dono:** Pedro.

**Estado:** esqueleto. Hoje só tem config validada, logs JSON e health checks.

## Responsabilidades

[RFC 0002](../../docs/rfc/0002-authentication-and-authorization.md) e [ADR-0002](../../docs/adr/0002-gateway-validates-jwt-locally-via-jwks.md):

- roteamento por prefixo para auth, user, order e inventory;
- validação local do JWT (RS256) com o JWKS do auth em cache;
- remover do cliente os headers `X-User-Id`/`X-Correlation-Id` e injetar os valores confiáveis;
- rate limit por IP/usuário e limite de tamanho do corpo;
- blacklist de `jti` no Redis próprio, alimentada por `TokenRevoked`.

## API

| Endpoint | Fase | Resposta |
|---|---|---|
| `GET /health/live` | F0 ✅ | `200 {"status":"up"}` |
| `GET /health/ready` | F0 ✅ | `200 {"status":"up","checks":{}}` |
| `/auth/*`, `/me*`, `/orders*`, `/events*` | F2 | proxy para o serviço dono |

## Eventos

| Publica | Consome (fila `ticketpro-gateway`) |
|---|---|
| — | `TokenRevoked` |

## Dados

Não tem Postgres. Usa um Redis próprio para a blacklist de `jti` e para o rate limit (F2/F3).

## Por onde começar

1. Ler a seção *Primeiros passos* do [README da raiz](../../README.md).
2. Ler:
   - [RFC 0002](../../docs/rfc/0002-authentication-and-authorization.md) §5.5 (validação no gateway) e §5.8 (OWASP);
   - [padrões](../../docs/padroes.md) §4 (HTTP e formato de erro).
3. **F1:** nada específico. O gateway já está `healthy`, e a lib `shared/go` (Tasks 3–4 do [plano F1](../../docs/superpowers/plans/2026-11-12-f1-platform.md)) entra quando o consumidor de `TokenRevoked` chegar.
4. **F2:** roteamento, JWT via JWKS e headers. **F3:** rate limit e `TokenRevoked` → Redis ([roadmap](../../docs/roadmap.md)).

## Variáveis de ambiente

| Variável | Obrigatória | Exemplo |
|---|---|---|
| `SERVICE_NAME` | sim | `gateway` |
| `PORT` | sim | `8080` |
| `LOG_LEVEL` | sim | `debug` \| `info` \| `warn` \| `error` |

## Comandos

```bash
make test        # go test
make lint        # gofmt + golangci-lint (container)
make typecheck   # go vet
make run         # sobe só este serviço (porta 8080)
```

Sem Go instalado, os alvos rodam sozinhos no container `golang:1.25`. Para forçar: `make test TOOLCHAIN=docker`. Também há um Dev Container em [`.devcontainer/`](.devcontainer/devcontainer.json).
