# ADR-0007: `UPDATE` atômico condicional contra venda dupla

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Pedro
- **Tags**: concorrência, R2

## Contexto e problema

A venda é por **setor e quantidade**. O F5 dispara 100 requisições concorrentes contra um setor com 10 unidades, processadas por 2+ réplicas do inventory. O resultado precisa ter **zero venda dupla** e exatamente 10 unidades reservadas.

## Opções consideradas

- Contador por setor com `UPDATE` condicional atômico
- Uma linha por unidade (`status='FREE'`) com `FOR UPDATE SKIP LOCKED`
- Lock otimista com coluna `version` + retry
- Lock distribuído (Redis)

## Decisão

O inventory guarda `sectors.available` e reserva com:

```sql
UPDATE sectors
   SET available = available - $qty
 WHERE id = $sectorId AND available >= $qty
RETURNING available;
```

Zero linhas afetadas significa `InventoryRejected(OUT_OF_STOCK)`. Na mesma transação são criados `reservations(order_id UNIQUE, qty, status=PENDING, expires_at)`, `processed_messages` e o outbox. Como rede de segurança há `CHECK (available >= 0 AND available <= capacity)`. A compensação (`OrderCancelled` ou expiração) faz `UPDATE reservations SET status='RELEASED' WHERE order_id=$1 AND status='PENDING'` e, só se essa linha foi afetada, `available = available + qty`. Isso torna a liberação idempotente.

### Consequências positivas

- O Postgres serializa as escritas na linha do setor, então não existe janela de leitura-depois-escrita.
- Quantidade > 1 sai numa única instrução.
- Não precisa de lock externo nem de fencing.

### Consequências negativas

- A linha do setor vira hotspot de contenção sob carga muito alta. É aceitável no volume da demo, e o caminho de evolução é fazer sharding do contador em N sub-linhas.
- Não serve para assento numerado (fora do escopo, ver arquitetura §12).

## Prós e contras

### Contador condicional ✅
- ✅ Correto, simples e com uma instrução.
- ❌ Hotspot.

### Linha por unidade + SKIP LOCKED
- ✅ Sem hotspot.
- ❌ Quantidade > 1 exige N locks e o volume de linhas cresce.

### Otimista com versão
- ✅ Sem lock.
- ❌ Os retries sob contenção alta (F5) geram tempestade de conflitos.

### Lock em Redis
- ❌ Exige fencing e é mais uma peça para falhar.

## Links

- [docs/arquitetura.md §6](../arquitetura.md), ADR-0006
