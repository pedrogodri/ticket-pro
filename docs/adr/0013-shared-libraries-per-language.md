# ADR-0013: Biblioteca interna por linguagem para Go e C#

- **Data**: 2026-11-12
- **Status**: Substituído por [ADR-0017](0017-vendored-platform-libraries-and-contracts.md)
- **Decisores**: Pedro, Gabriel, Yasmin
- **Tags**: código compartilhado, mensageria

## Contexto e problema

Go é usado em 4 componentes (gateway, inventory, notification, autoscaler) e C# em 2 (payment, user). Todos repetem a mesma infraestrutura de mensageria: envelope, cliente SNS/SQS, outbox + relay, idempotência, backoff e logging com `correlationId`. Reimplementar isso em cada serviço multiplica bugs justamente nos pontos que valem nota (R1, R6).

## Opções consideradas

- Biblioteca interna por linguagem no monorepo (`shared/go`, `shared/dotnet`)
- Copiar o código em cada serviço
- Pacotes publicados (Go module público / NuGet)

## Decisão

Criamos **`shared/go`** (módulo Go referenciado via `go.work`/`replace`) e **`shared/dotnet`** (class library `IngressoJa.Shared` referenciada por `ProjectReference`). Ambas têm escopo fechado:

| Pacote | Conteúdo |
|---|---|
| `envelope` | tipo `Envelope{messageId, correlationId, type, version, occurredAt, payload}` + validação contra `contracts/` |
| `messaging` | cliente SNS/SQS configurado por env, desembrulho do envelope SNS, atributos (`type`, `version`, `correlationId`, `traceparent`) |
| `outbox` | inserção na TX do chamador + relay `SKIP LOCKED` com backoff exponencial e jitter |
| `idempotency` | `processed_messages` (insert-or-skip na TX do chamador) |
| `retry` | backoff exponencial com jitter e limite, usado na reconexão ao broker |
| `logging` | logger JSON com `correlationId`/`traceId` no contexto |

**Java e TypeScript** implementam o mesmo contrato dentro de cada serviço, sem biblioteca compartilhada, porque cada um tem um serviço só. Os `contracts/` continuam sendo a fonte da verdade, e as bibliotecas nunca definem payloads de domínio.

**Regras:** sem lógica de negócio na lib, sem abstração especulativa (só entra o que 2+ serviços usam), e migrations de `outbox`/`processed_messages` ficam **em cada serviço** (a lib só documenta o schema esperado).

### Consequências positivas

- R1/R6 são implementados e testados uma vez por runtime.
- O comportamento fica uniforme e mais fácil de demonstrar.

### Consequências negativas

- O build Docker de serviços Go/C# usa o **contexto na raiz do repo** para enxergar `shared/`, o que deixa os Dockerfiles um pouco mais longos.
- Uma mudança na lib afeta vários serviços de uma vez. Mitigação: testes da lib + CI rodando todos os dependentes.
- Existem 4 implementações do mesmo contrato (lib Go, lib .NET, Java, TS). A divergência é contida por testes de contrato contra `contracts/`.

## Links

- ADR-0005, ADR-0006, ADR-0008, RFC-001
