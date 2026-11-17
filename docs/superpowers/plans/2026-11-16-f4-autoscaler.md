# F4 Autoscaler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Bônus 5 ([ADR-0015](../../adr/0015-queue-depth-autoscaling-docker-api.md)): o autoscaler lê a profundidade da fila e ajusta as réplicas do payment (e de outros alvos configurados) pela Docker Engine API, com demo de fila acumulada.

**Architecture:** O ciclo roda a cada `AUTOSCALER_INTERVAL_S` e, para cada alvo:
1. `queue.Depth` soma `ApproximateNumberOfMessages` e `NotVisible`;
2. `scaler.Decide` calcula `desired = clamp(ceil(depth / MSGS_PER_REPLICA), MIN, MAX)`, aplicando histerese: só reduz depois de `COOLDOWN_S` com o desejado abaixo do atual;
3. `docker.Client` (HTTP sobre `/var/run/docker.sock`, sem SDK) lista as réplicas pelas labels do compose. Para subir, clona uma réplica (imagem, env, labels, healthcheck, redes com alias do serviço). Para descer, para e remove as de maior `container-number`.

Toda decisão é logada (`depth`, `current`, `desired`).

**Tech Stack:** Go 1.25, aws-sdk-go-v2 sqs v1.52.7 e config v1.33.12 (mesmas versões dos outros serviços), Docker Engine API.

**Spec:** ADR-0015; `docs/arquitetura.md` §9; RFC 0003 §5.8 (F8).

## Global Constraints
- Envs (com padrão):
  - `AUTOSCALER_TARGETS=payment-service=ticketpro-payment-service`;
  - `AUTOSCALER_INTERVAL_S=5`;
  - `AUTOSCALER_MSGS_PER_REPLICA=5`;
  - `AUTOSCALER_MIN_REPLICAS=2`, `AUTOSCALER_MAX_REPLICAS=5`;
  - `AUTOSCALER_COOLDOWN_S=30`;
  - `COMPOSE_PROJECT_NAME=ticketpro`;
  - `DOCKER_HOST=unix:///var/run/docker.sock`.
- Nenhuma porta publicada. O socket do Docker é montado só no autoscaler (risco aceito na ADR, só para demo).
- Réplicas criadas levam as labels do compose (`project`, `service`, `container-number`) e `ticketpro.autoscaler=true`.

## Review Focus
1. **Fila vazia por um instante durante a vazão** → não reduz antes do cooldown (sem flapping). Teste na Task 1.
2. **Nunca abaixo de MIN nem acima de MAX**, inclusive com profundidade enorme ou réplicas extras criadas à mão. Teste na Task 1.
3. **Falha ao ler a fila ou ao falar com o Docker** → loga e não muda nada naquele ciclo; o loop continua. Teste na Task 3.
4. **Scale down remove só réplicas extras** (maior `container-number`), nunca as do compose abaixo do MIN. Teste na Task 2.
5. **Clone com nome único** (`<project>-<service>-<n>`, com n = maior + 1), sem colidir com réplicas existentes. Teste na Task 2.

### Task 1: Decisão (`internal/scaler`)
- [ ] `Decide(state, depth, current, now) (desired int, state)`: testes de clamp, de arredondamento para cima, de subir imediato e de descer só depois do cooldown, que é resetado se a pressão volta.

### Task 2: Cliente Docker (`internal/docker`)
- [ ] Testes com `httptest`:
  - lista por labels e ordena por `container-number`;
  - o clone envia o create com imagem/env/labels/healthcheck, nome e número novos, conecta as redes extras e inicia o container;
  - a remoção faz stop e delete.

### Task 3: Loop e wiring
- [ ] `internal/scaler.Loop` com fakes:
  - aplica o desejado;
  - erro de fila ou de Docker não muda nada e não derruba o loop.
- [ ] Config com validação (MIN ≤ MAX, alvos `serviço=fila`).
- [ ] Compose: socket montado, `user: "0"`, envs AWS.

### Task 4: Demo
- [ ] `scripts/demo-autoscale.sh`:
  - para o payment (`PAYMENT_LATENCY_MS` alto via override), acumula `PaymentRequested` direto na fila;
  - espera o autoscaler subir até MAX;
  - drena e espera voltar a MIN depois do cooldown.
- [ ] README do autoscaler. `happy-path.sh` continua verde.
