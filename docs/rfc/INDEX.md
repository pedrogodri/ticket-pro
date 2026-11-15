# RFCs

Propostas de design do Ticket Pro. As decisões fechadas viram ADRs em [`../adr/`](../adr/README.md). As RFCs guardam o detalhe de protocolo e o racional que os ADRs resumem.

## Allocating a number

The next free number is **0004**. Antes de criar uma RFC, confira a tabela abaixo (ou `ls` deste diretório) e use o próximo inteiro livre. Atualize esta tabela na mesma mudança.

Filename: `NNNN-kebab-title.md`. Keep the `# RFC NNNN — Title` H1 and the number in the filename in sync.

## Index

| # | Title | Status | One-line routing description |
|---|---|---|---|
| [0001](0001-message-contract-and-event-versioning.md) | Contrato de mensagens, envelope e versionamento de eventos | 📝 Draft | Formato único de toda mensagem assíncrona, como ela trafega em SNS/SQS e como eventos evoluem de versão sem quebrar consumidores. |
| [0002](0002-authentication-and-authorization.md) | Autenticação e autorização | 📝 Draft | Como usuários se autenticam (JWT + refresh), como o gateway valida e revoga tokens e onde a autorização por objeto é aplicada. |
| [0003](0003-failure-handling-strategy.md) | Estratégia de falhas | 📝 Draft | Retry, backoff, timeouts, DLQ, reconexão ao broker e compensações em cada camada, ligadas aos cenários F1–F8. |

## Status legend

- 📝 **Draft** — proposed, not started
- 🚧 **In progress** — partially shipped
- ✅ **Complete** — fully shipped
- ❌ **Rejected / withdrawn**
