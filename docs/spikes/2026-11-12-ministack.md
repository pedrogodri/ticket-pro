# Spike: comportamento do MiniStack (F0, 12/11/2026)

**Objetivo:** confirmar os comportamentos do MiniStack `1.5.40` de que a arquitetura depende antes de escrever a plataforma (F1).
**Script:** [`scripts/spikes/ministack-spike.sh`](../../scripts/spikes/ministack-spike.sh). Requer `docker compose up -d` e o `infra-init` aplicado. Para rodar a partir de um estado limpo: `docker compose up -d --force-recreate infra-init`.

## Resultado: 8/8 PASS

| id | Verificação | Resultado | Observação |
|---|---|---|---|
| S1-filter | Filter policy no atributo `type` roteia por consumidor | PASS | `OrderCreated` só chega no inventory; `PaymentRequested` só no payment |
| S2-body | Formato do corpo sem raw delivery | PASS | Envelope SNS (`Type=Notification`, `Message` = nosso envelope). Os atributos ficam **só** dentro do envelope SNS (`MessageAttributes.type.Value`) e **não** aparecem como atributos SQS |
| S3-raw | Raw message delivery | PASS | O corpo é o envelope puro, e `type`/`version` chegam como `MessageAttributes` SQS |
| S4-receivecount | `ApproximateReceiveCount` | PASS | Incrementa a cada receive sem delete (1 → 2) |
| S5-visibility0 | `ChangeMessageVisibility 0` | PASS | A mensagem volta a ficar visível na hora |
| S6-redrive | Redrive para a DLQ após `maxReceiveCount=3` | PASS | Depois de 3 receives, a mensagem sai da fila e aparece na DLQ |
| S7-depth | `ApproximateNumberOfMessages` | PASS | Reflete as 5 mensagens publicadas |
| S8-pause | `docker pause` por 30 s | PASS | Filas e mensagens preservadas. O publish durante a pausa falha no cliente por timeout, **mas é entregue depois do unpause** (ver achado 2) |

## Decisões

1. **Raw delivery ligado** em todas as assinaturas (`raw_message_delivery = true` no módulo `messaging`), conferido pelo `scripts/verify-infra.sh`. O consumidor recebe o envelope puro e lê `type`, `version`, `correlationId` e `traceparent` dos atributos SQS. O desembrulho do envelope SNS continua implementado como fallback (RFC 0001 §5.2), mas deixa de ser o caminho principal.
2. **Nenhum plano B foi acionado.** O redrive nativo (RFC 0003 §5.4), o `ChangeMessageVisibility(0)` para mensagens venenosas, a autoescala por `ApproximateNumberOfMessages` (ADR-0015) e o `docker pause` no F6 (arquitetura §9) funcionam como desenhado. Por isso não houve ADR novo.

## Achados que afetam a implementação

1. **O reinício do container MiniStack mantém S3 e Secrets Manager, mas perde SNS/SQS.** O Compose também roda o `infra-init` de novo a cada `docker compose up`. Por isso o state do Terraform fica no volume `tf-state`. Assim:
   - a reexecução é um no-op e preserva as mensagens nas filas;
   - depois de reiniciar o MiniStack, `docker compose up -d --force-recreate infra-init` recria só o que sumiu.

   Os dois casos são verificados por `scripts/check-infra-rerun.sh`. Uma primeira versão que fazia `POST /_ministack/reset` antes do apply foi descartada, porque apagava as filas a cada `up`.
2. **Um timeout no cliente não significa "não publicado".** Durante o `docker pause`, o kernel aceita a conexão TCP e o MiniStack processa a requisição ao voltar. Com os retries padrão da AWS CLI, **um** publish com timeout virou **4** entregas depois do `unpause`. Consequências, que já estão previstas e agora são obrigatórias:
   - consumidores idempotentes por `messageId` (ADR-0006);
   - retry interno dos SDKs reduzido a 1 tentativa (RFC 0003 §5.5 e decisão 7). Senão, cada tentativa vira uma publicação duplicada;
   - o relay do outbox reaproveita o mesmo `messageId` em toda republicação (RFC 0001, decisão 2).
3. **Em bash 3.2 (macOS), `docker run -i` dentro de `while read` consome o stdin do loop.** O helper `awsl` (`scripts/lib/aws.sh`) não usa `-i`.
