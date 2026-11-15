# Demo F2: caminho feliz

Checkpoint da F2 ([roadmap](../roadmap.md)): uma compra de ponta a ponta, só pelo gateway (`localhost:8080`).

## Rodar

```bash
docker compose down -v && docker compose up --build -d
scripts/wait-healthy.sh
scripts/happy-path.sh        # cadastro → login → catálogo → pedido → CONFIRMED → meus ingressos → comprovante no S3
scripts/check-replicas.sh    # 3 execuções do caminho feliz; cada réplica de order/inventory/payment consumiu mensagens
```

Requer `curl`, `jq` e `uuidgen`.

## O que acontece

1. `POST /auth/register` e `POST /auth/login`: o auth emite um JWT RS256; o gateway valida pela JWKS e injeta `X-User-Id`.
2. `GET /events` e `GET /events/{id}`: catálogo do inventory (seed na migration).
3. `POST /orders` com `Idempotency-Key`: o order grava `PENDING` + `OrderCreated` no outbox e responde `202` com `Location`.
4. Saga: inventory reserva (`InventoryReserved`), order pede pagamento (`PaymentRequested`), o worker do payment cobra no provedor simulado (`PaymentApproved`), order confirma (`OrderConfirmed`), inventory emite os ingressos (`TicketsIssued`).
5. `TicketsIssued` tem dois consumidores: user (read model `GET /me/tickets`) e notification (`s3://ticketpro-receipts/receipts/{orderId}.json`).
6. Repetir o `POST /orders` com a mesma chave devolve `200` com o mesmo pedido.

`PAYMENT_FAILURE_RATE` é 0.1 por padrão: um pedido pode terminar `CANCELLED(PAYMENT_FAILED)`, e o script tenta de novo (até 5 pedidos).
