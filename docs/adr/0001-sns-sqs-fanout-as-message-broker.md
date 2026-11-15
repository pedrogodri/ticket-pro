# ADR-0001: SNS + SQS (fan-out) como broker, com tópico por domínio

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Pedro, Felipe, Gabriel, Yasmin, Matheus
- **Tags**: mensageria, infraestrutura

## Contexto e problema

A mensageria assíncrona é obrigatória e a infraestrutura é AWS emulada (MiniStack, ADR-0009). Precisamos de fan-out (um evento para 2+ serviços), consumidores concorrentes na mesma fila (R4), DLQ para mensagem venenosa (F4) e entrega at-least-once (R1).

## Direcionadores

- Ser emulado pelo MiniStack e criado por IaC.
- Ter fan-out nativo e DLQ nativa.
- Ter SDK maduro em Go, Java, Node e .NET.
- Não exigir operar um broker à parte.

## Opções consideradas

- SNS → SQS (uma fila por consumidor)
- Somente SQS (o produtor envia para N filas)
- RabbitMQ / Kafka em container

## Decisão

Escolhemos **SNS → SQS**. Há um tópico SNS por **domínio produtor** (`ticketpro-auth`, `-users`, `-orders`, `-inventory`, `-payments`) e uma fila SQS por **serviço consumidor** (`ticketpro-<svc>`), assinada com **filter policy** no atributo `type`. Cada fila tem DLQ `ticketpro-<svc>-dlq` com `maxReceiveCount=3` (configurável no Terraform).

### Consequências positivas

- O fan-out vem de graça. Adicionar um consumidor é só uma assinatura nova, sem mudar o produtor.
- Réplicas de um serviço competem pela mesma fila, o que entrega R4 sem código extra.
- DLQ e visibility timeout dão reentrega e isolamento de veneno (F2, F4).

### Consequências negativas

- SQS standard **não garante ordem** e pode duplicar. Os consumidores precisam de idempotência (ADR-0006) e de transições de estado guardadas.
- O MiniStack guarda SNS/SQS só em memória: reiniciá-lo apaga filas e mensagens (F6 usa `docker pause`).
- Raw message delivery não é documentado no MiniStack, então os consumidores aceitam os dois formatos (mensagem crua e envelope SNS).

## Prós e contras

### SNS → SQS ✅
- ✅ Fan-out, DLQ e consumidores concorrentes nativos.
- ❌ Sem ordem e com duplicatas possíveis.

### Somente SQS
- ✅ Um recurso a menos.
- ❌ O produtor passa a conhecer todos os consumidores (acoplamento), e são N escritas por evento.

### RabbitMQ/Kafka
- ✅ Ordem por partição (Kafka) e roteamento rico.
- ❌ Não conta como "categoria de nuvem" AWS e adiciona um broker para operar.

## Links

- [docs/arquitetura.md §4](../arquitetura.md)
- RFC-001, RFC-003
