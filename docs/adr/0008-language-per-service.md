# ADR-0008: Linguagem por serviço

- **Data**: 2026-11-12
- **Status**: Aceito — versão do .NET substituída por [ADR-0018](0018-runtime-versions.md)
- **Decisores**: Grupo
- **Tags**: organização, linguagens

## Contexto e problema

O enunciado exige ≥3 linguagens em runtimes distintos. O grupo domina Go (Pedro), Java (Felipe), C# (Gabriel, Yasmin) e TypeScript (Matheus, apoio geral). Queremos alinhar cada serviço a quem vai mantê-lo e ao perfil técnico do serviço.

## Decisão

| Serviço | Linguagem | Responsável | Motivo |
|---|---|---|---|
| gateway | Go | Pedro | Proxy reverso eficiente na stdlib (`httputil.ReverseProxy`), baixa latência e binário pequeno |
| inventory-service | Go | Pedro | Concorrência e SQL explícito (`pgx`), crítico para R2 |
| notification-service | Go | Pedro (apoio Matheus) | Consumidor simples + SDK S3 |
| auth-service | Java (Spring Boot) | Felipe | Ecossistema maduro de segurança e JWT (Nimbus JOSE) |
| payment-service | C# (.NET 8) | Gabriel | `BackgroundService` e Polly para timeout/retry da dependência instável |
| user-service | C# (.NET 8) | Yasmin | EF Core + consumidor de projeção (CQRS) |
| order-service | TypeScript (Node 22) | Matheus | Orquestrador orientado a eventos, com I/O assíncrono natural |
| autoscaler | Go | Pedro | SDK Docker oficial em Go |

São 4 runtimes distintos (Go, JVM, .NET, Node).

### Consequências positivas

- Cada serviço tem um dono que domina a linguagem.
- Go e C# se repetem, o que justifica bibliotecas internas (ADR-0013).

### Consequências negativas

- O Pedro concentra 4 componentes em Go. Mitigação: o Matheus apoia o notification, e o gateway é sem estado e pequeno.
- Java e TypeScript implementam outbox, idempotência e envelope sozinhos, sem biblioteca compartilhada. Para evitar divergência, a conformidade é validada contra `contracts/` em testes.

## Opções consideradas

A distribuição sugerida no planejamento foi validada sem mudanças. Consideramos colocar o order em C# para aproveitar a biblioteca compartilhada, mas isso deixaria o TypeScript sem serviço e o Matheus sem dono claro.

## Links

- ADR-0013
