# ADR-0012: OpenTelemetry com Collector e Jaeger

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Grupo
- **Tags**: observabilidade, bônus

## Contexto e problema

A saga atravessa 4 serviços em 4 linguagens, passando por filas. Sem tracing distribuído, diagnosticar F2/F6/F7 na demo depende de cruzar logs na mão. O OpenTelemetry também é bônus.

## Opções consideradas

- OpenTelemetry SDK em cada serviço → OTel Collector → Jaeger
- Só logs com `correlationId`
- Agente/APM proprietário

## Decisão

Cada serviço usa o SDK OTel da sua linguagem, com auto-instrumentação HTTP/DB onde existir e spans manuais para publish/consume. A propagação é W3C `traceparent`: em HTTP pelo header, e em mensagens pelo **atributo SNS/SQS `traceparent`**. Os consumidores criam span `consumer` linkado ao span `producer`. Os dados seguem por OTLP/gRPC até o `otel-collector`, e de lá para o `jaeger` (all-in-one, UI na porta 16686). Configuração por env: `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`.

O `correlationId` do envelope continua obrigatório e independente do trace, e todo log carrega os dois.

### Consequências positivas

- Um trace mostra a saga inteira, inclusive a espera no provedor instável.
- O padrão é aberto e igual nas 4 linguagens.

### Consequências negativas

- A propagação via atributo de mensagem é manual em todos os serviços e entra na biblioteca compartilhada onde existir (ADR-0013).
- Mais 2 containers. O Jaeger fica em memória, sem persistência de traces entre execuções.

## Links

- ADR-0013, RFC-001 (atributos de mensagem)
