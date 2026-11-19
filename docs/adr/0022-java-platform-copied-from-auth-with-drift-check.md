# ADR-0022: Plataforma Java copiada do auth-service, com checagem de deriva (sem `shared/java`)

- **Data**: 2026-11-19
- **Status**: Aceito (2026-11-19)
- **Decisores**: Grupo
- **Tags**: código compartilhado, Java, plataforma
- **Relacionado**: [ADR-0017](0017-vendored-platform-libraries-and-contracts.md), [ADR-0021](0021-order-and-inventory-in-java.md), [RFC 0004](../rfc/0004-camada-platform-em-java-para-order-e-inventory.md)

## Contexto e problema

O ADR-0017 vendoriza `shared/go` e `shared/dotnet`; em Java, `platform/` é "código próprio do serviço" (padrões §2.2). O ADR-0021 deixou aberto: "se a duplicação pesar, um ADR novo pode propor `shared/java`". Ao planejar order e inventory em Java descobrimos que o `auth-service` **já tem** a plataforma completa e testada (envelope, `SqsConsumer`, `OutboxRelay`, idempotência, backoff, health, logs; testes P01–P16). Agora há três serviços Java e é preciso decidir como os dois novos ganham essa camada, a 5 dias do freeze (24/11).

## Direcionadores

- Prazo: a plataforma é caminho crítico de order e inventory.
- Não arriscar o `auth-service`, que funciona.
- Evitar três implementações que derivam em silêncio (R1 e R6 valem nota).
- ADR-0016: cada serviço compila sozinho a partir da própria pasta.

## Opções consideradas

- **A. Copiar o `platform/` do auth para order e inventory (só troca o pacote) e vigiar a deriva com um script.**
- **B. `shared/java` + `scripts/sync-shared.sh` (extensão do ADR-0017).**
- **C. Reescrever a plataforma em cada serviço.**

## Decisão

Escolhemos a **opção A**. Order e inventory recebem uma cópia do `platform/` do auth em `br.furb.ticketpro.<svc>.platform`, sem renomear classes. O script `scripts/check-java-platform.sh` compara as três árvores **normalizando o pacote** e falha se algum arquivo divergir fora de uma lista explícita de exceções (`AppConfig*`, `ApiExceptionHandler`, que são específicos de cada serviço). Correção de plataforma entra nos três serviços no mesmo PR. Código novo da plataforma (runners, jobs, `UserScopedTransaction`) nasce em order/inventory e volta para o auth quando ele precisar.

### Consequências positivas

- Plataforma pronta em horas, com os testes P01–P16 já escritos.
- Zero mudança no `auth-service` e no `sync-shared.sh`.
- A deriva é visível em CI, sem depender de disciplina.

### Consequências negativas

- Código triplicado até 02/12; um defeito do auth (ex.: o logger que serializa `duplicate=true` como string) vem junto na cópia e exige correção nos três.
- O script de deriva é mais um artefato a manter. A exceção para `AppConfig*`/`ApiExceptionHandler` precisa ser revisada se outros arquivos passarem a divergir.
- Se a duplicação pesar depois da banca, a migração para a opção B é mecânica (mover o conteúdo normalizado para `shared/java`), mas pede um novo ADR que substitua este.

## Prós e contras

### A. Cópia + checagem de deriva ✅
- ✅ Mais rápida e de menor risco no prazo; reaproveita testes.
- ❌ Triplicação; depende do script para não divergir.

### B. `shared/java` vendorizado
- ✅ Uma fonte da verdade, igual ao Go e ao C#.
- ❌ Exige reescrita de pacote no `sync-shared.sh` (com testes), migrar o auth e validar três serviços; consome dias que o freeze não dá.

### C. Reescrever
- ✅ Nenhuma dependência do auth.
- ❌ Refaz o que já está testado nos pontos que valem nota.

## Links

- ADR-0017, ADR-0021, [RFC 0004 §5.1, §5.10](../rfc/0004-camada-platform-em-java-para-order-e-inventory.md)
