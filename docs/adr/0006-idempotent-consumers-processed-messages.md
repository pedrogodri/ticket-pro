# ADR-0006: Consumidor idempotente com `processed_messages`

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Grupo
- **Tags**: idempotência, R1

## Contexto e problema

O SQS standard e o outbox entregam at-least-once. Uma mesma mensagem pode chegar várias vezes (F3: duplicata ×5, F2: reentrega após kill). O efeito precisa ocorrer exatamente uma vez.

## Opções consideradas

- Tabela `processed_messages` gravada na mesma transação do efeito
- Somente idempotência natural (constraints de negócio)
- Deduplicação em Redis antes de processar

## Decisão

Todo consumidor faz, numa única transação:

1. `INSERT INTO processed_messages (consumer, message_id, processed_at) VALUES (...)`, com PK `(consumer, message_id)`;
2. se houver conflito, a mensagem é duplicata: faz **rollback, ack e log `duplicate=true`**;
3. senão, aplica o efeito + grava o outbox (se houver) + **commit**;
4. só **depois do commit**, dá ack (`DeleteMessage`).

Como reforço, existem constraints naturais: `reservations.order_id UNIQUE`, `payments.order_id UNIQUE`, `my_tickets.ticket_code UNIQUE` e transições `WHERE status = <esperado>`.

**Trabalho lento** (chamada ao provedor de pagamento) não acontece dentro do processamento da mensagem. O consumidor grava a intenção (`payments PENDING`) e dá ack. Um worker com lease executa a chamada usando `paymentId` como idempotency key. Isso evita que o visibility timeout expire no meio da chamada e cause cobrança dupla.

### Consequências positivas

- Duplicatas não têm efeito, o que é demonstrável no F3 contando linhas.
- Ack-after-commit: crash antes do commit leva a reentrega, nunca a perda.

### Consequências negativas

- `processed_messages` cresce. É limpa por job após `PROCESSED_MESSAGES_RETENTION_D` (padrão 7), bem acima da retenção da fila.
- Exige banco transacional em todo consumidor (inclusive o notification, que de outro modo só escreveria no S3).
- O efeito no S3 não é transacional. Por isso o objeto usa chave determinística (`receipts/{orderId}.json`), e a reescrita é idempotente.

## Prós e contras

### `processed_messages` na TX ✅
- ✅ Garantia forte e uniforme nas 4 linguagens.
- ❌ Tabela extra.

### Só constraints naturais
- ✅ Sem tabela extra.
- ❌ Nem todo efeito tem chave natural (ex.: notificação de cancelamento).

### Redis antes de processar
- ❌ Não é atômico com o efeito: crash entre o Redis e o banco perde a mensagem.

## Links

- ADR-0005, RFC-003
