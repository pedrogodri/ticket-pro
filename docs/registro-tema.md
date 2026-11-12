# Registro do Tema — Trabalho Final de Sistemas Distribuídos (FURB)

**Tema:** IngressoJá, venda online de ingressos por setor (estilo Ticketmaster). Somente backend.

> O enunciado cita o IngressoJá como exemplo. O professor confirmou que o grupo pode usá-lo como tema.

## Integrantes

| Integrante | Linguagem principal | Responsabilidade |
|---|---|---|
| Pedro | Go | gateway, inventory-service, notification-service |
| Felipe | Java | auth-service |
| Gabriel | C# | payment-service |
| Yasmin | C# | user-service |
| Matheus | TypeScript (apoio geral) | order-service, apoio aos demais |

## Serviços e linguagens (4 runtimes distintos)

| Serviço | Linguagem | Banco próprio |
|---|---|---|
| gateway | Go | — (sem banco; Redis para rate limit) |
| auth-service | Java | PostgreSQL `auth` + Redis (blacklist de `jti`) |
| user-service | C# | PostgreSQL `users` |
| order-service | TypeScript (Node.js) | PostgreSQL `orders` |
| inventory-service | Go | PostgreSQL `inventory` |
| payment-service | C# | PostgreSQL `payments` |
| notification-service | Go | PostgreSQL `notifications` + bucket S3 |

## Nuvem

Usamos o **MiniStack** (AWS emulada localmente, `AWS_ENDPOINT_URL=http://ministack:4566`). Tudo sobe com `docker compose up`, e os recursos são criados por script de init (Terraform). Categorias de nuvem usadas:

1. **Mensageria (obrigatória):** SNS (tópicos) com uma fila SQS por consumidor, cada uma com DLQ.
2. **Armazenamento de objetos:** S3, para os comprovantes gerados pelo notification-service.
3. **Segurança / segredos:** Secrets Manager, para a chave de assinatura JWT do auth-service.

## Fluxo principal: compra de ingressos

O cliente faz `POST /orders` (evento, setor, quantidade) e recebe **202 + id do pedido**. O order-service **orquestra uma saga**. O inventory-service reserva o estoque do setor com um `UPDATE` atômico condicional (zero venda dupla). Depois o payment-service cobra num **provedor de pagamento simulado, lento e instável** (latência e taxa de falha configuráveis por variável de ambiente, com timeout). Se o pagamento falhar ou estourar o prazo, o pedido é cancelado e a **compensação** devolve o estoque reservado. Se a aprovação chegar tarde, depois do cancelamento, o pagamento é estornado. Com o pagamento aprovado, o pedido é confirmado, o inventory emite os ingressos e o evento `TicketsIssued` é **consumido por dois serviços**: o user-service atualiza "meus ingressos" (modelo de leitura CQRS) e o notification-service grava o comprovante no S3.

**Elementos obrigatórios:**

1. **Transação distribuída com compensação:** saga reserva → pagamento → confirmação. Em caso de falha, `OrderCancelled` libera a reserva.
2. **Dependência lenta/instável simulada:** provedor de pagamento (`PAYMENT_LATENCY_MS`, `PAYMENT_FAILURE_RATE`).
3. **Evento consumido por 2+ serviços:** `TicketsIssued` é consumido pelo user-service e pelo notification-service. `OrderCancelled` é consumido pelo inventory-service e pelo notification-service.
