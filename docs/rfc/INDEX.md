# RFCs

Propostas de design do Ticket Pro. As decisões fechadas viram ADRs em [`../adr/`](../adr/README.md). As RFCs guardam o detalhe de protocolo e o racional que os ADRs resumem.

## Allocating a number

The next free number is **0013**. Antes de criar uma RFC, confira a tabela abaixo (ou `ls` deste diretório) e use o próximo inteiro livre. Atualize esta tabela na mesma mudança.

Filename: `NNNN-kebab-title.md`. Keep the `# RFC NNNN — Title` H1 and the number in the filename in sync.

## Index

| # | Title | Status | One-line routing description |
|---|---|---|---|
| [0001](0001-message-contract-and-event-versioning.md) | Contrato de mensagens, envelope e versionamento de eventos | 📝 Draft | Formato único de toda mensagem assíncrona, como ela trafega em SNS/SQS e como eventos evoluem de versão sem quebrar consumidores. |
| [0002](0002-authentication-and-authorization.md) | Autenticação e autorização | 📝 Draft | Como usuários se autenticam (JWT + refresh), como o gateway valida e revoga tokens e onde a autorização por objeto é aplicada. |
| [0003](0003-failure-handling-strategy.md) | Estratégia de falhas | 📝 Draft | Retry, backoff, timeouts, DLQ, reconexão ao broker e compensações em cada camada, ligadas aos cenários F1–F8. |
| [0004](0004-camada-platform-em-java-para-order-e-inventory.md) | Camada platform em Java para order e inventory | 🚧 In progress | Como order e inventory ganham a camada platform (consumo SQS, outbox, idempotência, roles de banco, jobs, logs) a partir do que o auth-service já tem em Java. |
| [0005](0005-design-do-order-service-em-java.md) | Design do order-service em Java | 🚧 In progress | API assíncrona de pedidos e orquestração da saga de compra no order-service: transições condicionais, deadline scanner e estorno tardio. |
| [0006](0006-design-do-inventory-service-em-java.md) | Design do inventory-service em Java | 📝 Draft | Catálogo, reserva atômica anti-overselling, ingressos, devolução e expiração de estoque no inventory-service, incluindo o upcaster do OrderCreated. |
| [0007](0007-design-do-auth-service-em-java.md) | Design do auth-service em Java | 📝 Draft | Refresh rotativo, logout com TokenRevoked e compensação do cadastro no auth-service, a partir do que já existe (register, login, JWKS). |
| [0008](0008-design-do-user-service-em-csharp.md) | Design do user-service em C# | 📝 Draft | Perfil, GET /me, read model CQRS /me/tickets e a publicação de UserCreationFailed no user-service, com a regra de BOLA por RLS. |
| [0009](0009-design-do-payment-service-em-csharp.md) | Design do payment-service em C# | 🚧 In progress | Consumidor, worker com lease, provedor simulado instável (R5) e estorno no payment-service, com os ajustes de config e correlationId que faltam. |
| [0010](0010-design-do-gateway-em-go.md) | Design do gateway em Go | 📝 Draft | Roteamento, JWT via JWKS, rate limit e revogação por evento no gateway, incluindo a correção da rota pública de refresh. |
| [0011](0011-design-do-notification-service-em-go.md) | Design do notification-service em Go | 📝 Draft | Comprovante idempotente no S3 e registro de notificações a partir de TicketsIssued e OrderCancelled. |
| [0012](0012-design-do-autoscaler-em-go.md) | Design do autoscaler em Go | 📝 Draft | Autoescala por profundidade de fila via Docker API (ADR-0015) e a questão do sinal de carga do payment, que consome e dá ack. |

## Status legend

- 📝 **Draft** — proposed, not started
- 🚧 **In progress** — partially shipped
- ✅ **Complete** — fully shipped
- ❌ **Rejected / withdrawn**
