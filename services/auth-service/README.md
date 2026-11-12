# auth-service

Credenciais, emissão de JWT e JWKS do IngressoJá, em Java 21 com Spring Boot 4.0. Nesta fase (F0) o serviço só expõe os health checks. O cadastro, o login, o refresh e a chave no Secrets Manager chegam na F2 (RFC 0002).

| Endpoint | Resposta |
|---|---|
| `GET /health/live` | `200 {"status":"up"}` |
| `GET /health/ready` | `200 {"status":"up","checks":{}}` |

## Variáveis de ambiente

| Variável | Obrigatória | Exemplo |
|---|---|---|
| `SERVICE_NAME` | sim | `auth-service` |
| `PORT` | sim | `8080` |
| `LOG_LEVEL` | sim | `debug` \| `info` \| `warn` \| `error` |

## Comandos

```bash
make test        # JUnit 5
make lint        # Spotless (google-java-format)
make typecheck   # compila com warnings como erro
make run         # sobe só este serviço
```
