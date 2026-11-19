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
| 04 Usuário | `/me`, `/me/tickets`, isolamento entre usuários |
| 05 F1–F8 | os 8 cenários da demo; passos de infraestrutura são manuais (comando na descrição) |
| 06 Bônus | tracing, autoscale, versionamento |

**⏳ = pendente.** Endpoints que ainda não existem (hoje 404): `/auth/refresh`, `/auth/logout`, `/events*`, `/orders*`. A collection descreve o contrato-alvo (RFC 0002/0005) e passa a validar sozinha quando order e inventory forem entregues. Hoje funcionam health, register, login, `/me`, `/me/tickets` e as regras do gateway.

Linha de comando (Newman):

```bash
npx newman run docs/postman/ticket-pro.postman_collection.json \
  -e docs/postman/ticket-pro.local.postman_environment.json \
  --folder "00 · Setup (rode primeiro)" --folder "01 · Auth" --folder "04 · Usuário (user-service)"
```

As falhas de infraestrutura (F1, F2, F6, F7, F8) exigem `docker` e não existem via HTTP; os scripts `scripts/f1-*.sh` … `f8-*.sh` continuam sendo a forma canônica.
