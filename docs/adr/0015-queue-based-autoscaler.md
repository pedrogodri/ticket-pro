# ADR-0015: Autoescala por profundidade de fila via Docker API

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Pedro
- **Tags**: escala, bônus

## Contexto e problema

O bônus pede autoescala por fila. O ambiente é Docker Compose, sem Kubernetes (logo, sem KEDA) e sem ECS real. O payment-service, por causa da dependência lenta, é o gargalo natural.

## Opções consideradas

- Container `autoscaler` (Go) que lê métricas SQS e ajusta réplicas pela Docker Engine API
- Migrar para k3d/kind + KEDA
- Escala manual com `docker compose --scale` (não conta como autoescala)

## Decisão

Um container `autoscaler` em Go, com acesso a `/var/run/docker.sock`, faz a cada `AUTOSCALER_INTERVAL_S`:

1. lê `ApproximateNumberOfMessages` (+ `NotVisible`) das filas configuradas em `AUTOSCALER_TARGETS` (ex.: `payment-service=ticketpro-payment-service`);
2. calcula `desired = clamp(ceil(depth / AUTOSCALER_MSGS_PER_REPLICA), MIN, MAX)`;
3. aplica histerese: só reduz depois de `AUTOSCALER_COOLDOWN_S` estável;
4. cria ou remove containers do serviço clonando a config de uma réplica existente (mesma imagem, env, redes e labels do compose).

Toda decisão é logada (`depth`, `current`, `desired`).

### Consequências positivas

- A escala usa o sinal correto (backlog da fila), e não CPU.
- Integra com o F8: o mesmo comportamento que o `--scale 3` manual, só que automático.

### Consequências negativas

- Montar o Docker socket dá ao autoscaler controle total do host Docker. É aceitável em ambiente local de demo, mas inaceitável em produção.
- Containers criados fora do `compose up` não aparecem no `docker compose ps` padrão. Mitigação: usar as labels `com.docker.compose.project/service` para que apareçam.
- **Risco:** o MiniStack não documenta `ApproximateNumberOfMessages`. Se não funcionar, o plano B é cada consumidor expor `GET /metrics/lag` (mensagens recebidas − processadas na janela) e o autoscaler usar isso. A validação acontece em spike na fase 1.

## Links

- [docs/arquitetura.md §9](../arquitetura.md)
