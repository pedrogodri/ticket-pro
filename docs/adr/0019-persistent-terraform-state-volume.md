# ADR-0019: State do Terraform persistido em volume

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Grupo
- **Tags**: IaC, infraestrutura
- **Substitui parcialmente**: [ADR-0011](0011-terraform-applied-by-init-container.md) (somente "state efêmero")

## Contexto e problema

O ADR-0011 previa state efêmero no `infra-init`. Na F0 apareceram dois fatos que derrubam essa escolha:

1. o Docker Compose **roda o `infra-init` de novo a cada `docker compose up`**, inclusive num `up -d --scale` durante a demo (F8);
2. o MiniStack perde SNS/SQS ao reiniciar, mas mantém S3 e Secrets Manager (spike de 12/11).

Com state efêmero, a reexecução falhava com "already exists". A primeira correção, que resetava o MiniStack antes do apply, **apagava as mensagens nas filas** a cada `up`.

## Decisão

O state fica no volume nomeado `tf-state` (backend `local`, `path = /state/terraform.tfstate`). O `infra-init` não reseta o MiniStack. Os efeitos:

- **reexecução normal:** o apply é um no-op e as mensagens são preservadas;
- **MiniStack reiniciado:** `docker compose up -d --force-recreate infra-init`, e o refresh recria só os recursos que sumiram;
- **`docker compose down -v`:** remove MiniStack e state juntos, e o próximo `up` começa limpo.

Os dois primeiros casos são verificados por `scripts/check-infra-rerun.sh`.

### Consequências

- ✅ `up`/`--scale` durante a demo nunca apagam filas.
- ✅ Recuperação de restart do MiniStack sem perder S3/segredo.
- ❌ Apagar só o volume `tf-state` com o MiniStack ainda de pé faz o apply falhar ("already exists"). A saída é `docker compose down -v`.

## Links

- ADR-0011, [docs/spikes/2026-11-12-ministack.md](../spikes/2026-11-12-ministack.md)
