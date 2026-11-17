# Demo F4: versionamento de eventos

Bônus de versionamento ([RFC 0001 §5.5](../rfc/0001-message-contract-and-event-versioning.md)). O `OrderCreated` tem duas versões:
- **v1:** a original;
- **v2:** v1 + `channel` obrigatório (`API`, `WEB` ou `MOBILE`).

## Rodar

```bash
scripts/demo-versioning.sh
```

O script publica três mensagens direto no tópico `ticketpro-orders`, como faria o outbox do order:

| Mensagem | O que acontece |
|---|---|
| v1 | o inventory converte para v2 com `channel = API` (upcaster) e reserva |
| v2 (`WEB`) | o inventory reserva e grava `channel = WEB` |
| v3 | versão não suportada: venenosa, vai para `ticketpro-inventory-service-dlq` sem efeito |

## Pelo order

O order publica a versão definida em `ORDER_CREATED_VERSION`, com padrão `2`. Em v2, o `channel` é sempre `API`, porque todo pedido entra pela API HTTP.

Para mostrar o v1 vindo do próprio order:

```bash
ORDER_CREATED_VERSION=1 docker compose up -d order-service
scripts/happy-path.sh
docker compose up -d order-service   # volta para v2
```

## Onde está

- Upcaster: `services/inventory-service/internal/modules/reservations/handlers.go` (`upcastOrderCreated`).
- Canal da reserva: migration `V0007__add_reservation_channel.sql`.
- Publicação: `services/order-service/src/modules/orders/application/createOrder.ts`.
