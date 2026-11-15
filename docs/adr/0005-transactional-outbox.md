# ADR-0005: Transactional outbox com relay por polling

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Grupo
- **Tags**: mensageria, confiabilidade

## Contexto e problema

Gravar no banco e publicar no SNS são duas operações em sistemas diferentes (dual-write). Se o processo cair entre elas, perdemos o evento ou publicamos algo que não foi persistido. O R6 também exige que publicações feitas durante uma queda do broker sejam entregues quando ele voltar.

## Opções consideradas

- Outbox na mesma transação + relay por polling (`SKIP LOCKED`)
- Outbox + CDC (Debezium)
- Publicar direto após o commit, com retry em memória

## Decisão

Todo serviço que publica tem uma tabela `outbox (id, message_id, topic, type, version, correlation_id, payload jsonb, created_at, published_at, attempts, next_attempt_at)`. O evento é inserido **na mesma transação** do efeito de negócio. Um relay em cada réplica faz:

```sql
SELECT ... FROM outbox
WHERE published_at IS NULL AND next_attempt_at <= now()
ORDER BY created_at
LIMIT $batch
FOR UPDATE SKIP LOCKED
```

O relay publica no SNS e marca `published_at`. Em caso de erro, incrementa `attempts` e agenda `next_attempt_at` com **backoff exponencial com jitter**, limitado por `OUTBOX_MAX_BACKOFF_MS`. Linhas publicadas são limpas após `OUTBOX_RETENTION_H`.

### Consequências positivas

- Não há dual-write: o evento existe se e somente se o efeito foi commitado.
- O broker fora do ar só acumula linhas, e o relay drena sozinho ao voltar (F6, R6).
- Funciona com N réplicas sem coordenação.

### Consequências negativas

- Garante **at-least-once**: se o relay cair entre o publish e o `UPDATE`, o evento é republicado. Por isso os consumidores precisam ser idempotentes (ADR-0006).
- O polling adiciona latência (`OUTBOX_POLL_INTERVAL_MS`, padrão 200 ms) e carga leve no banco.
- A ordem entre réplicas não é garantida, o que é tratado com transições guardadas.

## Prós e contras

### Polling ✅
- ✅ Simples e implementável nas 4 linguagens.
- ❌ Latência de polling.

### CDC
- ✅ Baixa latência.
- ❌ Debezium + Kafka Connect é infraestrutura demais para o prazo.

### Publish pós-commit
- ❌ Perde eventos em crash e não satisfaz R6.

## Links

- ADR-0006, ADR-0013, RFC-003
