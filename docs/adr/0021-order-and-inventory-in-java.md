# ADR-0021: order-service e inventory-service em Java

- **Data**: 2026-11-18
- **Status**: Aceito — substitui a linha do order-service e do inventory-service no [ADR-0008](0008-language-per-service.md)
- **Decisores**: Grupo
- **Tags**: organização, linguagens

## Contexto e problema

O ADR-0008 colocou o order-service em TypeScript (Node 22) e o inventory-service em Go. O grupo redistribuiu os donos: o order-service e o inventory-service passam a ser escritos em Java (Spring Boot), pelos devs que já mantêm Java no projeto.

## Decisão

| Serviço | Antes (ADR-0008) | Agora | Responsável |
|---|---|---|---|
| order-service | TypeScript (Node 22) | Java 21 (Spring Boot 4.0) | Matheus |
| inventory-service | Go | Java 21 (Spring Boot 4.0) | Felipe |

As demais linhas do ADR-0008 não mudam. O código TypeScript e Go antigos dos dois serviços foi removido; ficam `migrations/`, `compose.yaml`, `*.env.example`, `contracts/` e o README, que descreve o que o serviço deve fazer. A reimplementação em Java segue o contrato do README de cada serviço, o RFC 0003 e o [`docs/padroes.md`](../padroes.md) §2.2.

Resultado: Go (gateway, notification, autoscaler), Java (auth, order, inventory) e C# (payment, user). Seguem sendo 3 linguagens em 3 runtimes distintos (Go, JVM, .NET), o que atende ao enunciado (≥3 linguagens).

### Consequências positivas

- Java concentra os serviços de domínio com mais transação (saga, estoque), e o Spring dá `@Transactional`, Flyway e testes de integração com Testcontainers já usados no auth-service.
- Não há mais serviço em Node. O Node 22 continua só como ferramenta de `contracts/` (`make -C contracts check`).

### Consequências negativas

- Três serviços Java implementam outbox, idempotência e envelope sem biblioteca compartilhada (ADR-0017 só vendoriza Go e C#). A conformidade é validada contra `contracts/` em testes. Se a duplicação pesar, um ADR novo pode propor `shared/java`.
- Até a reimplementação terminar, `order-service` e `inventory-service` não têm `Dockerfile`, `Makefile` nem `.devcontainer`: o `docker compose up --build` não sobe a stack completa, e o `check-autonomy.sh` não se aplica a eles.
- O `inventory-service` sai do `shared/go` e do `scripts/sync-shared.conf`. Os contratos continuam vendorizados em `services/<svc>/contracts/`.
- O critério R2 (100 reservas concorrentes num setor com 10 unidades dão exatamente 10) deve ser reescrito como teste Java, com threads em vez de goroutines.

## Opções consideradas

- **Manter TS e Go:** descartado, porque os donos dos serviços mudaram.
- **Portar o código existente junto com a troca:** descartado; quem for implementar em Java parte do README e dos contratos, sem herdar a estrutura das outras linguagens.

## Links

- ADR-0008, ADR-0013, ADR-0017, ADR-0018
