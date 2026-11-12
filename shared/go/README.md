# shared/go — plataforma de mensageria em Go

Fonte da verdade da camada de plataforma dos serviços Go (ADR-0017). **Não importe este módulo direto:** o `scripts/sync-shared.sh` copia os pacotes para `services/<svc>/internal/platform/` e reescreve o import path. Altere aqui, suba o `VERSION` e rode o sync.

| Pacote | Conteúdo |
|---|---|
| `retry` | `Delay(attempt, base, cap, rnd)`: backoff exponencial com full jitter (RFC 0003 §5.2) |
| `envelope` | `Envelope`, `New`, `Unwrap` (SNS ou raw), `Validator` contra `contracts/`, erros `ErrPoison`/`ErrUnsupportedVersion`/`ErrInvalidMessage` |
| `tracing` | `Inject`/`Extract` de `traceparent` (W3C) |
| `logging` | `New(w, service, level)` com as chaves de padrões §5 e `WithCorrelationID` |
| `idempotency`, `outbox`, `messaging` | `processed_messages`, outbox + relay e consumidor SQS (Task 4 do plano F1) |

O contrato completo, igual nas 4 linguagens, está na seção *Platform contract* de [`docs/superpowers/plans/2026-11-12-f1-platform.md`](../../docs/superpowers/plans/2026-11-12-f1-platform.md).

## Dependências que o serviço precisa no `go.mod`

O sync roda `go mod tidy` no serviço, então elas entram sozinhas: `github.com/google/uuid`, `github.com/santhosh-tekuri/jsonschema/v6`, `go.opentelemetry.io/otel` (v1.46.x: a v1.47 exige Go 1.26, e o ADR-0018 fixa Go 1.25). Depois da Task 4 entram também `github.com/jackc/pgx/v5` e `github.com/aws/aws-sdk-go-v2` (sns, sqs).

## Comandos

```bash
make test               # unitários (P01–P06)
make test-integration   # Testcontainers: Postgres + MiniStack (P07–P15)
make lint typecheck
```

Os testes não são vendorizados (`*_test.go`, `testdata/`, `internal/`), por isso podem ler `../../contracts`.
