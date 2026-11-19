# RFC 0001 — Contrato de mensagens, envelope e versionamento de eventos

- **Status:** 📝 Draft (design fechado — implementação na fase 1 do roadmap)
- **Scope:** Define o envelope único de toda mensagem assíncrona, o mapeamento para SNS/SQS (corpo + atributos), a organização de `contracts/` e a política de evolução de versões, incluindo a demo `OrderCreated` v1 → v2. Não cobre APIs HTTP síncronas (ver `docs/padroes.md`) nem semântica de retry (RFC 0003).
- **Related:** [ADR-0001](../adr/0001-sns-sqs-fanout-as-message-broker.md), [ADR-0006](../adr/0006-idempotent-consumers-processed-messages.md), [ADR-0013](../adr/0013-shared-libraries-per-language.md) (substituído por [ADR-0017](../adr/0017-vendored-platform-libraries-and-contracts.md)), [arquitetura §7](../arquitetura.md), `contracts/`

---

## 1. Summary

Toda mensagem é um JSON com seis campos fixos (`messageId, correlationId, type, version, occurredAt, payload`), publicado como `Message` do SNS. `type`, `version`, `correlationId` e `traceparent` também vão como atributos de mensagem, usados para filtro e tracing. Cada par `type`+`version` tem um JSON Schema em `contracts/events/<Type>/v<N>.schema.json`, e o catálogo completo fica em `contracts/asyncapi.yaml`. A evolução segue *tolerant reader* + *consumer-first rollout*, e `OrderCreated` v2 demonstra o processo.

## 2. Motivation

São 7 serviços em 4 linguagens trocando 15 tipos de evento. Sem um contrato único e validável, cada time interpreta campos do seu jeito e o problema só aparece em runtime, na demo. O R1 (idempotência) depende de `messageId` estável, o tracing depende de `correlationId`/`traceparent`, e o bônus de versionamento exige uma regra explícita.

## 3. Current state

Repositório vazio de código. Os eventos e quem publica/consome estão definidos em [arquitetura §7](../arquitetura.md).

## 4. Goals / Non-goals

**Goals**

- Um envelope idêntico nas 4 linguagens, validável por JSON Schema.
- Consumidores roteiam por `type`+`version` sem desserializar o payload.
- Evoluir um evento sem parar os consumidores.

**Non-goals**

- Schema Registry em runtime (Confluent/Glue). Os schemas são arquivos no repo, validados em teste e no consumo.
- Avro/Protobuf. JSON é legível na demo e suportado nativamente por SNS/SQS.

## 5. Design

### 5.1 Envelope

```json
{
  "messageId": "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55",
  "correlationId": "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c",
  "type": "OrderCreated",
  "version": 1,
  "occurredAt": "2026-11-20T18:30:12.345Z",
  "payload": { "orderId": "…", "userId": "…", "eventId": "…", "sectorId": "…", "quantity": 2 }
}
```

| Campo | Tipo | Regra |
|---|---|---|
| `messageId` | string UUID (v7 preferido, v4 aceito) | Gerado **uma vez**, no insert do outbox. A republicação reaproveita o mesmo valor. É a chave de idempotência (ADR-0006). |
| `correlationId` | string UUID | Nasce no gateway (`X-Correlation-Id`) ou no job que inicia o fluxo. Todo evento produzido em reação a outro **copia** o `correlationId` de entrada. |
| `type` | string PascalCase, fato no passado (`OrderCreated`) ou comando no imperativo-substantivo (`PaymentRequested`) | Igual ao nome da pasta em `contracts/events/`. |
| `version` | inteiro ≥ 1 | Versão do **payload** daquele `type`. |
| `occurredAt` | string RFC 3339 em UTC com milissegundos | Momento do efeito de negócio (commit), não do publish. |
| `payload` | objeto | Validado por `contracts/events/<type>/v<version>.schema.json`. |

O envelope é fechado (`additionalProperties: false`). Dentro do `payload` os schemas também são fechados, mas o **consumidor** ignora campos desconhecidos (tolerant reader). Assim o schema pega erro do produtor e o consumidor não quebra com adição.

Convenções do payload: IDs como string UUID, dinheiro em centavos inteiros (`amountCents`) + `currency` ISO 4217, datas RFC 3339 UTC, enums em `UPPER_SNAKE_CASE`. Não entra PII além de `userId`/`email` onde for necessário. Tamanho máximo de 64 KB por mensagem (o limite do SNS é 256 KB).

### 5.2 Transporte em SNS/SQS

- `Publish` no tópico do domínio com `Message = JSON.stringify(envelope)`.
- `MessageAttributes` (todos `DataType=String`): `type`, `version`, `correlationId`, `traceparent` (se houver).
- As assinaturas SQS usam `FilterPolicy` em `type`, por exemplo `{"type": ["OrderCreated","OrderConfirmed","OrderCancelled"]}`.
- **Desembrulho no consumidor:** se o corpo SQS tiver `"Type": "Notification"` e `"Message"`, ele faz parse de `Message` (envelope SNS). Senão, trata o corpo como o próprio envelope (raw delivery). Os dois caminhos são obrigatórios, porque o MiniStack não documenta raw delivery.
- O consumidor valida o envelope e depois o payload. Falha de validação é **mensagem venenosa** (RFC 0003 §5.4).

### 5.3 Organização de `contracts/`

```
contracts/
├── asyncapi.yaml                       # AsyncAPI 3.1: canais (tópicos), operações, mensagens → $ref nos schemas
├── envelope.schema.json                # envelope genérico (payload: object)
├── common.schema.json                  # $defs: uuid, money, timestamp…
├── events/
│   ├── OrderCreated/
│   │   ├── v1.schema.json
│   │   └── v2.schema.json
│   └── <Type>/v<N>.schema.json
└── examples/<Type>.v<N>.json           # exemplo válido por versão (usado em teste)
```

Draft do JSON Schema: **2020-12**. Cada `v<N>.schema.json` descreve a **mensagem inteira** (envelope com `type`/`version` fixos via `const` + `payload` específico), de modo que um único arquivo valida uma mensagem real.

### 5.4 Versionamento

| Mudança no payload | Nova versão? | Procedimento |
|---|---|---|
| Adicionar campo **opcional** | Não | Atualiza `vN.schema.json`. Consumidores ignoram até usarem. |
| Adicionar campo **obrigatório**, remover, renomear ou mudar tipo/semântica | **Sim (`vN+1`)** | Novo arquivo; `vN` permanece. |
| Remover uma versão | — | Só quando nenhum produtor a emite **e** a retenção da fila (4 dias) já passou. |

**Rollout consumer-first:** (1) os consumidores passam a aceitar `vN` e `vN+1`, (2) o produtor passa a emitir `vN+1`, (3) depois da janela, os consumidores podem remover `vN`.

**No consumidor:** cada handler declara as versões suportadas. Um *upcaster* converte versões antigas para o modelo interno mais novo, e só existe um caminho de processamento. Versão **não suportada** é tratada como venenosa e vai para a DLQ com log `unsupported_version`. Não é descartada em silêncio.

### 5.5 Demo: `OrderCreated` v1 → v2

- **v1:** `{orderId, userId, eventId, sectorId, quantity}`
- **v2:** v1 + `channel` **obrigatório** (`API | WEB | MOBILE`), que indica a origem da compra para métricas no inventory.
- O inventory implementa o upcaster `v1 → v2` (`channel = "API"`).
- O order publica v2 controlado por `ORDER_CREATED_VERSION` (padrão `2`).
- Roteiro: publicar v1 (env = 1) e v2 alternadamente, mostrando que os dois são processados. Depois injetar uma mensagem `version: 3` e mostrar que ela vai para a DLQ.

### 5.6 Catálogo de eventos (resumo dos payloads)

| Evento | v | Payload |
|---|---|---|
| `UserRegistered` | 1 | `userId, email, name` |
| `UserCreationFailed` | 1 | `userId, reason` |
| `TokenRevoked` | 1 | `jti, userId, expiresAt, reason (LOGOUT \| ACCOUNT_REMOVED \| REFRESH_REUSE)` |
| `OrderCreated` | 1, 2 | ver §5.5 |
| `PaymentRequested` | 1 | `orderId, userId, amountCents, currency` |
| `OrderConfirmed` | 1 | `orderId, userId, eventId, sectorId, quantity, totalCents, currency, confirmedAt` |
| `OrderCancelled` | 1 | `orderId, userId, reason (OUT_OF_STOCK \| PAYMENT_FAILED \| PAYMENT_TIMEOUT \| RESERVATION_EXPIRED), cancelledAt` |
| `RefundRequested` | 1 | `orderId, paymentId, amountCents, currency, reason (LATE_APPROVAL)` |
| `InventoryReserved` | 1 | `orderId, reservationId, eventId, sectorId, quantity, unitPriceCents, totalCents, currency, expiresAt` |
| `InventoryRejected` | 1 | `orderId, eventId, sectorId, quantity, reason (OUT_OF_STOCK \| SECTOR_NOT_FOUND \| INVALID_QUANTITY)` |
| `InventoryReservationExpired` | 1 | `orderId, reservationId, quantity` |
| `TicketsIssued` | 1 | `orderId, userId, eventId, eventName, eventStartsAt, sectorId, sectorName, tickets[{ticketId, code}]` |
| `PaymentApproved` | 1 | `orderId, paymentId, amountCents, currency, providerReference, approvedAt` |
| `PaymentFailed` | 1 | `orderId, paymentId, reason (DECLINED \| PROVIDER_ERROR \| PROVIDER_TIMEOUT)` |
| `PaymentRefunded` | 1 | `orderId, paymentId, refundId, amountCents, currency` |

O **preço** é do inventory: ele o informa em `InventoryReserved`, e o order usa esse valor no `PaymentRequested`. O `TicketsIssued` carrega nomes e datas do evento para que o read model do user não precise consultar o catálogo (CQRS sem leitura cruzada).

## 6. Tests

- CI: `npm run validate` em `contracts/` (ajv 2020-12 + `@asyncapi/parser`) compila todos os schemas, valida cada `examples/*.json`, exige que cada `examples/invalid/*.json` seja rejeitado e checa se o `asyncapi.yaml` referencia todo schema.
- Em cada serviço: teste de contrato que serializa os eventos produzidos e os valida contra `contracts/`, e que lê `examples/` como entrada dos handlers.
- Teste do upcaster `OrderCreated` v1 → v2 no inventory.

## 7. Docs

`docs/padroes.md` referencia esta RFC para nomes de eventos, filas e atributos. Toda mensagem nova exige schema + exemplo + linha no `asyncapi.yaml` no mesmo PR.

## 8. Out of scope

- Ordenação garantida (FIFO). O SQS standard é suficiente com transições guardadas. A porta de saída seria SNS/SQS FIFO com `MessageGroupId = orderId`.
- Criptografia de payload. Ambiente emulado, sem dado sensível.

## 9. Risks

- **Divergência entre 4 implementações do envelope.** Mitigação: os testes de contrato da §6 e as libs `shared/` em Go/.NET.
- **Formato SNS vs raw no MiniStack.** Mitigação: os consumidores aceitam os dois (§5.2).
- **Schema fechado no payload quebra o produtor que adiciona campo sem atualizar o schema.** Isso é intencional: obriga a atualizar o contrato junto.

## 10. Unresolved questions

- Se o MiniStack honra `RawMessageDelivery=true`. Sai da spike da fase 1 e não muda o design.

## 11. Decisions

| # | Grade | Decision |
| --- | --- | --- |
| 1 | `LOCKED` | O envelope tem exatamente seis campos (`messageId, correlationId, type, version, occurredAt, payload`) e é fechado. Mudar isso afeta os 7 serviços e as libs. |
| 2 | `LOCKED` | `messageId` é gerado no insert do outbox e reaproveitado em toda republicação. É a base de R1. |
| 3 | `LOCKED` | `version` é inteiro por `type`. Mudança incompatível cria `v<N+1>` em arquivo novo, com rollout consumer-first. |
| 4 | `ASSUMED` | `type`, `version`, `correlationId` e `traceparent` são duplicados como atributos SNS/SQS para filter policy e tracing. |
| 5 | `ASSUMED` | JSON Schema 2020-12 por mensagem inteira + AsyncAPI 3.1 como catálogo. |
| 6 | `ASSUMED` | Versão desconhecida é tratada como venenosa (vai para a DLQ), nunca descartada em silêncio. |
| 7 | `ASSUMED` | Dinheiro em centavos inteiros + ISO 4217. O preço vem do inventory em `InventoryReserved`. |
| 8 | `OPEN` | UUID v7 vs v4 para `messageId` em cada linguagem. Usar v7 onde houver lib estável, senão v4. Os dois são válidos no schema. |

## 12. Phasing

- **P1:** `envelope.schema.json`, `common.schema.json`, schemas v1 de todos os eventos, exemplos e validação em CI (esta etapa de docs).
- **P2:** libs `shared/go` e `shared/dotnet` (envelope + desembrulho SNS), equivalente em Java.
- **P3 (bônus):** `OrderCreated` v2 + upcaster + roteiro de demo.
