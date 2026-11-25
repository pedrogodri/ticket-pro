# Collection Postman do Ticket Pro

Tudo passa pelo gateway (`http://localhost:8080`). Arquivos:

- `ticket-pro.postman_collection.json`: collection v2.1 (importa também no Bruno e no Insomnia).
- `ticket-pro.local.postman_environment.json`: `baseUrl`, `password` e `f5SectorId`.

Suba a stack (`docker compose up --build -d && scripts/wait-healthy.sh`), importe os dois arquivos e rode a pasta **00 · Setup** primeiro (cria os usuários A e B e descobre evento/setor).

| Pasta | Conteúdo |
|---|---|
| 00 Setup | health, usuários A/B, evento e setor do seed |
| 01 Auth | caminho feliz + 401/409/400/413/404, token lixo, JWKS não exposto |
| 02 Catálogo | listagem, detalhe, 404, 401 |
| 03 Pedidos | saga ponta a ponta (202 → estado final), idempotência, validações, BOLA, rate limit |
| 04 Usuário | `/me`, `/me/tickets`, `/me/orders` (read model CQRS), isolamento entre usuários |
| 05 F1–F8 | os 8 cenários da demo; passos de infraestrutura são manuais (comando na descrição) |
| 06 Bônus | trace de um pedido no Jaeger, autoscale, versionamento v1/v2/v3 |
| 07 Checagens por serviço | `payment-check.sh`, `notification-check.sh` e `gateway-check.sh` (revogação e rate limit) |

Todos os serviços estão entregues e a collection valida sozinha. **⏳** sobra só em `/auth/refresh` e `/auth/logout`: o auth-service implementa register, login e JWKS, e essas duas rotas respondem 404. A revogação de token é demonstrada por `scripts/gateway-check.sh`, que publica `TokenRevoked` direto no tópico `ticketpro-auth`.

**payment-service e notification-service:** só consomem e publicam eventos, sem rota HTTP de negócio, então não viram request. A pasta 07 traz os scripts que os verificam isolados: `scripts/payment-check.sh` (aprovação, idempotência, estorno único, `DECLINED` e timeout) e `scripts/notification-check.sh` (comprovante no S3 e notificação únicos para 5 entregas). Os passos de F1, F7 e F8 que recriam o payment trazem o comando `docker compose` na descrição; no F7 o `PAYMENT_WORKER_LEASE_MS` precisa ser maior que o `PAYMENT_PROVIDER_TIMEOUT_MS`.

Rode a pasta 03 com 500 ms entre requests (Runner: *Delay* 500; Newman: `--delay-request 500`): o gateway limita os pedidos a 2/s por usuário (`ORDERS_RPS=2`, `ORDERS_BURST=5`) e responde 429 em rajada.

Linha de comando (Newman):

```bash
npx newman run docs/postman/ticket-pro.postman_collection.json \
  -e docs/postman/ticket-pro.local.postman_environment.json \
  --folder "00 · Setup (rode primeiro)" --folder "01 · Auth" --folder "04 · Usuário (user-service)"
```

As falhas de infraestrutura (F1, F2, F6, F7, F8) exigem `docker` e não existem via HTTP; os scripts `scripts/f1-*.sh` … `f8-*.sh` continuam sendo a forma canônica. Na apresentação, use `scripts/demo.sh`: ele mostra a previsão de cada cenário, roda o script e depois exibe o estado nos bancos e nos logs (veja `docs/runbook.md`).
