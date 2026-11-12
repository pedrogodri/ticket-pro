# contracts/

Fonte da verdade das mensagens assíncronas do IngressoJá. As regras completas estão na [RFC 0001](../docs/rfc/0001-message-contract-and-event-versioning.md).

| Caminho | Conteúdo |
|---|---|
| `envelope.schema.json` | Envelope único: `messageId, correlationId, type, version, occurredAt, payload` |
| `common.schema.json` | Tipos reutilizados (`uuid`, `timestamp`, `amountCents`, `currency`, `quantity`, `email`) |
| `events/<Type>/v<N>.schema.json` | Mensagem completa (envelope + payload) de cada versão de evento, com `x-topic`, `x-producer`, `x-consumers` |
| `examples/<Type>.v<N>.json` | Um exemplo válido por versão (usado também em testes dos serviços) |
| `examples/invalid/*.json` | Mensagens que **devem** ser rejeitadas (base do cenário F4 e do teste de versão não suportada) |
| `asyncapi.yaml` | Catálogo AsyncAPI 3.1: tópicos SNS, mensagens, quem publica e quem consome (fila e DLQ) |

## Validar

```bash
cd contracts && npm ci && npm run validate && npm run typecheck
```

O `validate` falha (exit 1) quando:

- algum schema não compila;
- falta exemplo para alguma versão de evento;
- algum exemplo válido não passa;
- algum exemplo inválido passa;
- o `asyncapi.yaml` tem erro ou não referencia algum schema.

## Adicionar ou alterar um evento

1. Mudança compatível (campo opcional): edite o `vN.schema.json` existente.
2. Mudança incompatível: crie `v<N+1>.schema.json`, mantendo o `vN`, e siga o rollout consumer-first (RFC 0001 §5.4).
3. Adicione/atualize `examples/<Type>.v<N>.json`.
4. Adicione a mensagem e as operações no `asyncapi.yaml`.
5. Atualize o mapa de eventos em [`docs/arquitetura.md` §7](../docs/arquitetura.md).
6. Rode `npm run validate`. Tudo no mesmo PR.
