# RFC 0012 — Design do autoscaler em Go

- **Status:** 📝 Draft (serviço implementado; este RFC fixa o contrato e registra a questão do sinal de carga — [plano](../superpowers/plans/2026-11-19-autoscaler.md))
- **Scope:** O `autoscaler` (Go): lê a profundidade das filas SQS, decide o número de réplicas e cria/remove containers pela Docker Engine API (bônus de autoescala, F8). Implementa o ADR-0015, **sem substituí-lo**. Registra uma questão aberta sobre o sinal usado (Decisão 1) que pode exigir ADR novo. **Sem mudança de contrato; sem banco.**
- **Related:** [ADR-0015](../adr/0015-queue-based-autoscaler.md), [RFC 0003 §5.6, §5.8](0003-failure-handling-strategy.md), [arquitetura §9](../arquitetura.md), [`services/autoscaler/`](../../services/autoscaler/README.md), [`scripts/demo-autoscale.sh`](../../scripts/demo-autoscale.sh), [`scripts/f8-scale.sh`](../../scripts/f8-scale.sh), [`docs/spikes/2026-11-12-ministack.md`](../spikes/2026-11-12-ministack.md)

---

## 1. Summary

A cada `AUTOSCALER_INTERVAL_S` lê `ApproximateNumberOfMessages + NotVisible` das filas de `AUTOSCALER_TARGETS`, calcula `clamp(ceil(depth / MSGS_PER_REPLICA), MIN, MAX)`, sobe imediato, desce só após `COOLDOWN_S` estável, clonando uma réplica existente e removendo as de maior número com 10 s de graça. Toda decisão sai no log como `scale_decision`.

## 2. Motivation

É o bônus 5 do roadmap (primeiro a ser cortado) e a parte automática do F8. Valor para a apresentação: mostrar a fila acumulada e as réplicas subindo e drenando.

## 3. Current state

Verificado em 19/11 em `services/autoscaler`:

| Item | Estado | Evidência |
|---|---|---|
| Leitura de atributos SQS e resolução da URL (refaz após `QueueDoesNotExist`) | ✅ | `internal/queue`, commit `62171af` |
| Política `Target`/`Decide` com histerese e cooldown | ✅ | `internal/scaler/decide.go` + testes |
| Docker API sobre socket unix: listar réplicas por label do compose, clonar, remover, preservar redes/alias/healthcheck | ✅ | `internal/docker/client.go` + testes |
| Demo `scripts/demo-autoscale.sh` | ✅ | pausa as réplicas, acumula 40 `PaymentRequested`, espera 5 réplicas, drenar, voltar a 2 |
| Spike S7 (atributo no MiniStack) | ✅ validado | arquitetura §9, `docs/spikes/` |
| Alvo padrão | só `payment-service=ticketpro-payment-service` | `.env.example`; a arquitetura §9 e o ADR citam também o inventory |
| README | link do ADR-0015 aponta para `0015-queue-depth-autoscaling-docker-api.md` (inexistente; o arquivo é `0015-queue-based-autoscaler.md`) | link quebrado |
| Healthcheck | binário `/autoscaler healthcheck` | `compose.yaml` |

## 4. Goals / Non-goals

**Goals**

- Demonstração reprodutível de subir e descer réplicas por fila.
- Nenhuma réplica duplicada ou órfã; decisões auditáveis no log.

**Non-goals**

- Produção: o socket do Docker montado é controle total do host (ADR-0015, aceito só para demo).
- Escala por CPU/latência; KEDA/ECS.

## 5. Design

### 5.1 Algoritmo

`desired = clamp(ceil(depth / AUTOSCALER_MSGS_PER_REPLICA), MIN, MAX)`; `desired > current` aplica na hora; `desired < current` só depois de `COOLDOWN_S` contínuos com alvo menor. Clone: mesma imagem, env, redes (com alias do serviço), healthcheck e labels `com.docker.compose.*`. Remoção: maior número primeiro, parada com 10 s.

### 5.2 Sinal de carga — questão aberta

O consumidor do payment **só grava `payments(PENDING)` e dá ack**; o trabalho lento fica no worker. Logo a profundidade da fila SQS fica baixa mesmo com muitos pagamentos pendentes: o backlog real está na tabela `payments`, não na fila. A demo acumula mensagens **pausando** as réplicas, o que mostra o mecanismo mas não o caso de uso real. Isso contradiz a motivação do ADR-0015 ("o payment é o gargalo natural"), pois o ADR não distinguiu fila de trabalho pendente. Opções (decisão do dono, Pedro; **este RFC não escolhe**):

1. Manter como está e declarar que a demo é de mecanismo (custo zero, honesto).
2. Somar a fila ao número de `payments PENDING` (o autoscaler precisaria de acesso ao banco: viola ADR-0004).
3. O payment expõe a contagem (`GET /metrics/pending`, plano B já descrito no ADR-0015) e o autoscaler a consulta por HTTP interno.
4. Escalar o **inventory** (fila `ticketpro-inventory-service`, onde o backlog é de mensagens reais).

A opção 3 ou 2 mudaria uma decisão aceita e exigiria **ADR novo** que substitua o 0015; as opções 1 e 4 cabem no ADR atual (4 só com `AUTOSCALER_TARGETS`, sem código).

### 5.3 Falhas

| Falha | Comportamento |
|---|---|
| Fila inexistente | resolve de novo no próximo tick |
| Docker socket indisponível | log de erro, tenta no tick seguinte, sem derrubar |
| Clone parcial | remove o clone quebrado; réplica que sumiu conta como removida |
| `compose up` durante a demo | volta o serviço ao número do compose e o autoscaler sobe de novo no tick seguinte (documentado) |
| Redução sequencial | cada remoção espera até 10 s; outros alvos ficam sem checagem nesse intervalo (aceito com 1 alvo) |

### 5.4 Observabilidade e configuração

Log `scale_decision {target, depth, current, desired}`. Env (já em `.env.example`): `AUTOSCALER_TARGETS`, `AUTOSCALER_INTERVAL_S` (5), `AUTOSCALER_MSGS_PER_REPLICA` (5), `AUTOSCALER_MIN_REPLICAS` (2), `AUTOSCALER_MAX_REPLICAS` (5), `AUTOSCALER_COOLDOWN_S` (30), `COMPOSE_PROJECT_NAME`, `DOCKER_HOST`.

## 6. Tests

Existentes: política (`decide_test`), loop com fakes, cliente Docker contra um servidor HTTP falso, profundidade da fila. A acrescentar: nada de código; um teste ao vivo é o `demo-autoscale.sh` e o `f8-scale.sh` (que **para** o autoscaler para medir o `--scale 3`).

## 7. Docs

Corrigir o link do README; documentar a Decisão 1 no README e no runbook (o que a demo prova).

## 8. Out of scope

Segundo alvo por padrão, métricas Prometheus, autenticação do socket.

## 9. Risks

- **Narrativa fraca na banca** se perguntarem por que a fila fica vazia (§5.2). Mitigação: ter a resposta pronta e escolher a opção 1 ou 4.
- **Autoscaler e `f8-scale.sh` disputam as réplicas:** o script já o para; qualquer script novo sobre o payment deve fazer o mesmo.
- **Corte de escopo:** é o primeiro bônus a cortar (roadmap F4). Cortar não derruba R1–R6 nem F1–F8, só o item F8 "automático" (o `--scale 3` manual continua).

## 10. Unresolved questions

- Escolha entre as opções do §5.2. Dono: Pedro, com o grupo antes do ensaio de 24/11.

## 11. Decisions

| # | Grade | Decision |
|---|---|---|
| 1 | `OPEN` | **Substituída pela #4.** Sinal de carga do payment (§5.2). Se for a opção 2 ou 3, abrir ADR que substitua o 0015. |
| 2 | `LOCKED` | Escala pela Docker API com réplicas clonadas e labels do compose (ADR-0015). |
| 3 | `ASSUMED` | Alvo padrão continua só o payment; o inventory é opcional por env. |
| 4 | `ASSUMED` | Resolve a #1 (24/11, Pedro): **opção 1 da §5.2**. O sinal continua sendo a profundidade da fila e a demo é apresentada como prova do **mecanismo** (fila → decisão → Docker API → vazão), dizendo que o backlog real do payment fica na tabela `payments`. Sem código e sem ADR novo: cabe no ADR-0015. A opção 3 (o payment expõe a contagem de `PENDING` por HTTP) fica como evolução, com ADR que substitua o 0015. Ensaio da demo (S03): duas execuções seguidas, ~40 s cada, sem réplicas órfãs. |

## 12. Phasing

Já entregue. Resta documentação e a decisão da Decisão 1; qualquer mudança de sinal só entra se houver tempo antes do freeze.
