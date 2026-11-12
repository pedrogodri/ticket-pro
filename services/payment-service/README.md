# payment-service

Pagamentos do Ticket Pro, em C# com .NET 10. Simula um provedor de pagamento lento e instável (RFC 0003 §5.6). Nesta fase (F0) o serviço só expõe os health checks. O consumidor de `PaymentRequested`, o worker com lease e o provedor simulado chegam na F2.

| Endpoint | Resposta |
|---|---|
| `GET /health/live` | `200 {"status":"up"}` |
| `GET /health/ready` | `200 {"status":"up","checks":{}}` |

## Variáveis de ambiente

| Variável | Obrigatória | Exemplo |
|---|---|---|
| `SERVICE_NAME` | sim | `payment-service` |
| `PORT` | sim | `8080` |
| `LOG_LEVEL` | sim | `debug` \| `info` \| `warn` \| `error` |

## Comandos

```bash
make test        # xUnit
make lint        # dotnet format --verify-no-changes
make typecheck   # build com warnings como erro
make run         # sobe só este serviço
```
