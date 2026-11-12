# order-service

API assíncrona de pedidos e orquestrador da saga de compra do IngressoJá, em TypeScript com Node 22. Nesta fase (F0) o serviço só expõe os health checks. O resto chega na F2 (`docs/arquitetura.md` §6):

- `POST /orders` com resposta 202;
- `GET /orders/{id}`;
- a saga.

| Endpoint            | Resposta                          |
| ------------------- | --------------------------------- |
| `GET /health/live`  | `200 {"status":"up"}`             |
| `GET /health/ready` | `200 {"status":"up","checks":{}}` |

## Variáveis de ambiente

| Variável       | Obrigatória | Exemplo                                |
| -------------- | ----------- | -------------------------------------- |
| `SERVICE_NAME` | sim         | `order-service`                        |
| `PORT`         | sim         | `8080`                                 |
| `LOG_LEVEL`    | sim         | `debug` \| `info` \| `warn` \| `error` |

## Comandos

```bash
make test        # Vitest
make lint        # ESLint (typescript-eslint strict, sem any) + Prettier
make typecheck   # tsc --noEmit (strict)
make run         # sobe só este serviço
```
