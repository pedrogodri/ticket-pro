# gateway

Ponto de entrada único do IngressoJá, escrito em Go. Nesta fase (F0) ele só expõe os health checks. O resto chega na F2 (RFC 0002):

- roteamento;
- validação de JWT via JWKS;
- rate limit;
- injeção de `X-User-Id` e `X-Correlation-Id`.

| Endpoint | Resposta |
|---|---|
| `GET /health/live` | `200 {"status":"up"}` |
| `GET /health/ready` | `200 {"status":"up","checks":{}}` |

## Variáveis de ambiente

| Variável | Obrigatória | Exemplo |
|---|---|---|
| `SERVICE_NAME` | sim | `gateway` |
| `PORT` | sim | `8080` |
| `LOG_LEVEL` | sim | `debug` \| `info` \| `warn` \| `error` |

## Comandos

```bash
make test        # testes unitários
make lint        # gofmt + golangci-lint (container)
make typecheck   # go vet
make run         # sobe só este serviço (porta 8080)
```
