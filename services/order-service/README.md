# order-service

API assíncrona de pedidos e orquestrador da saga de compra do Ticket Pro, em Java 21 (Spring Boot 4).

**Dono:** Matheus.

**Estado:** implementado conforme a [RFC 0005](../../docs/rfc/0005-design-do-order-service-em-java.md) (T09–T11 do [plano](../../docs/superpowers/plans/2026-11-19-java-order-inventory.md)): API, saga, deadline scanner e estorno de aprovação tardia, sobre a camada `platform/` da [RFC 0004](../../docs/rfc/0004-camada-platform-em-java-para-order-e-inventory.md). O ponta a ponta (`scripts/happy-path.sh`, F1–F8) depende do inventory-service, que ainda não tem código.

## Responsabilidades

- **R3:** `POST /orders` responde `202` + `Location`, com `Idempotency-Key` obrigatório. O cliente consulta `GET /orders/{id}`.
- Máquina de estados da saga e compensações ([arquitetura §6](../../docs/arquitetura.md), [RFC 0003 §5.7](../../docs/rfc/0003-failure-handling-strategy.md)): toda transição é um `UPDATE … WHERE id = ? AND status = <esperado>`; evento duplicado, atrasado ou fora de ordem não muda nada (`stale_event`).
- Deadline scanner de pagamento e estorno de aprovação tardia (F7).
- Retenção (ADR-0005/0006): o `RetentionJob` apaga em lotes as linhas do `outbox` publicadas há mais de `OUTBOX_RETENTION_H` e as de `processed_messages` mais antigas que `PROCESSED_MESSAGES_RETENTION_D`. Linha ainda não publicada nunca é apagada.
- `OrderCreated` v1/v2 controlado por `ORDER_CREATED_VERSION` (bônus de versionamento).
- **BOLA:** pedido de outro usuário → `404`; o SQL filtra por `user_id` e a RLS (`app.user_id`) é a segunda barreira.

## API (via gateway)

Identidade só pelo `X-User-Id` injetado pelo gateway; erros em `application/problem+json` com título PT-BR.

| Endpoint | Sucesso | Erros |
| --- | --- | --- |
| `GET /health/live` | `200 {"status":"up"}` | — |
| `GET /health/ready` | `200 {"status":"up","checks":{"db":"up","broker":"up"}}` (broker fora = `degraded`, ainda 200) | `503` com o banco fora |
| `POST /orders` `{eventId, sectorId, quantity}` + `Idempotency-Key` (UUID) | `202` + `Location: /orders/{id}` + `{orderId, status:"PENDING"}`; chave repetida → `200` + pedido | `400` (campo desconhecido, UUID inválido, `quantity` fora de 1..`ORDER_MAX_QUANTITY`, header ausente), `401` sem `X-User-Id` |
| `GET /orders/{id}` | `200 {orderId, status, eventId, sectorId, quantity, totalCents, currency, cancelReason, createdAt}` | `404` (inexistente, de outro usuário ou id inválido) |
| `GET /orders?limit=&cursor=` | `200 {items, nextCursor}` (`limit` 1..50, padrão 20, mais novos primeiro) | `400` cursor/limit inválido |

## Eventos

| Consome (fila `ticketpro-order-service`) | Efeito | Publica (tópico `ticketpro-orders`, via outbox) |
| --- | --- | --- |
| — (`POST /orders`) | `PENDING` | `OrderCreated` v2 (v1 com `ORDER_CREATED_VERSION=1`) |
| `InventoryReserved` | `PENDING → AWAITING_PAYMENT`, preço do evento, prazo `now() + ORDER_PAYMENT_TIMEOUT_MS` | `PaymentRequested` |
| `InventoryRejected` | `PENDING → CANCELLED(OUT_OF_STOCK)` (qualquer `reason`, Decisão 4) | `OrderCancelled` |
| `InventoryReservationExpired` | `PENDING/AWAITING_PAYMENT → CANCELLED(RESERVATION_EXPIRED)` | `OrderCancelled` |
| `PaymentApproved` no prazo | `AWAITING_PAYMENT → CONFIRMED` | `OrderConfirmed` |
| `PaymentApproved` tardio | cancela (`PAYMENT_TIMEOUT`) se ainda não cancelado; `refund_status = REQUESTED` uma vez só | `OrderCancelled` (se cancelou agora) + `RefundRequested` |
| `PaymentFailed` | `AWAITING_PAYMENT → CANCELLED(PAYMENT_FAILED)` | `OrderCancelled` |
| `PaymentRefunded` | `refund_status = REFUNDED` | — |
| — (deadline scanner) | `AWAITING_PAYMENT` vencido → `CANCELLED(PAYMENT_TIMEOUT)` | `OrderCancelled` |

## Código

```
br.furb.ticketpro.order
├── platform/        # RFC 0004: cópia do auth-service (ADR-0022) + SqsConsumerRunner, jobs/, db/UserScopedTransaction, outbox/RetentionJob, tracing/Spans
└── modules/orders/
    ├── web/            OrderController, CreateOrderRequest, OrderResponse, CursorCodec
    ├── application/    CreateOrder, OrderQueries, SagaHandlers, DeadlineScanner, OrderEvents
    ├── domain/         OrderStatus, CancelReason, OrderView, Transitions (SQL das transições)
    └── infrastructure/ OrderRepository (JDBC)
```

Escolha do dono ([ADR-0023](../../docs/adr/0023-internal-architecture-chosen-per-service.md)): um módulo de negócio (`orders`) com camadas dentro. A saga concentra as regras em `domain/Transitions` (o SQL condicional de cada transição) e mantém o controller e os consumidores finos, só traduzindo a entrada para os casos de uso de `application/`.

A API usa só o pool `app` (`orders_app`, RLS); consumidor, relay e scanner usam só o pool `worker` (`orders_worker`, `BYPASSRLS`). O `platform/` em Java é código do serviço (não é cópia gerada): correção nele deve entrar também nos outros serviços Java (ADR-0022).

## Observabilidade

A imagem traz o agente OpenTelemetry Java 2.34.0, com versão e sha256 fixados no `Dockerfile` ([ADR-0012](../../docs/adr/0012-opentelemetry-tracing.md), RFC 0004 §5.9). Ele só é carregado quando `OTEL_EXPORTER_OTLP_ENDPOINT` está definida e instrumenta HTTP, JDBC e AWS SDK. O `tracing/Spans` cria os spans de mensageria iguais aos do `shared/go`:

- `process <tipo>` (consumer): filho do produtor e com link para ele; as queries do handler ficam dentro dele.
- `publish <tipo>` (producer): no relay, filho do `traceparent` gravado no outbox; o `traceparent` da mensagem passa a ser o dele.

Os laços de polling (relay, consumidor e jobs) rodam sob um contexto não amostrado (`Spans.unsampled()`), e o health check do Docker manda um `traceparent` com flag `00`. Assim o Jaeger (`http://localhost:16686`) mostra só pedidos e mensagens, sem as queries de fundo.

## Dados

O banco é `orders`, no container `orders-db`. Tabelas: `orders`, `outbox` e `processed_messages`. Constraints-chave: `(user_id, idempotency_key) UNIQUE`, `CHECK status IN (...)`, `CHECK quantity BETWEEN 1 AND 6`, RLS `FORCE` com a política `orders_owner`.

## Variáveis de ambiente

| Variável | Obrigatória | Padrão / exemplo |
| --- | --- | --- |
| `SERVICE_NAME`, `PORT`, `LOG_LEVEL` | sim | `order-service`, `8080`, `info` |
| `DATABASE_URL` | sim | `postgres://orders_app:…@orders-db:5432/orders` (API, RLS) |
| `DATABASE_WORKER_URL` | sim | role `orders_worker` (relay, consumidor, scanner) |
| `ORDERS_TOPIC_ARN`, `QUEUE_URL` | sim | `ticketpro-orders`, `ticketpro-order-service` |
| `CONTRACTS_DIR` | sim | `/app/contracts` |
| `AWS_REGION`, `AWS_ENDPOINT_URL` | sim / não | `us-east-1`, `http://ministack:4566` |
| `OUTBOX_POLL_INTERVAL_MS`, `OUTBOX_BATCH_SIZE`, `OUTBOX_MAX_BACKOFF_MS`, `OUTBOX_CLAIM_LEASE_MS`, `HTTP_CLIENT_TIMEOUT_MS` | não | 200, 50, 30000, 30000, 3000 |
| `CONSUMER_CONCURRENCY`, `SQS_WAIT_TIME_S`, `SHUTDOWN_TIMEOUT_S` | não | 4 (pool worker = +4), 10, 30 |
| `ORDER_PAYMENT_TIMEOUT_MS` | não | 15000 |
| `ORDER_DEADLINE_SCAN_INTERVAL_MS`, `ORDER_DEADLINE_SCAN_BATCH_SIZE` | não | 1000, 50 |
| `ORDER_MAX_QUANTITY` | não | 6 (o boot falha se > 6) |
| `ORDER_CREATED_VERSION` | não | 2 (1 ou 2) |
| `OUTBOX_RETENTION_H`, `PROCESSED_MESSAGES_RETENTION_D` | não | 24, 7 (o job de retenção apaga o outbox já publicado e o `processed_messages` mais antigos que isso) |
| `RETENTION_INTERVAL_MS`, `RETENTION_BATCH_SIZE` | não | 60000, 500 |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | não | `http://otel-collector:4317` (com esquema). Sem ela, o agente OTel não é carregado |
| `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_PROTOCOL`, `OTEL_METRICS_EXPORTER`, `OTEL_LOGS_EXPORTER` | não | `order-service`, `grpc`, `none`, `none` |
| `RESERVATION_TTL_MS`, `RESERVATION_SAFETY_MARGIN_MS` | não | 60000, 30000 — o boot falha se `RESERVATION_TTL_MS < ORDER_PAYMENT_TIMEOUT_MS + RESERVATION_SAFETY_MARGIN_MS` |

O banco e o job de migration usam `db.env.example` (owner e senhas dos roles, só desenvolvimento). `contracts/` é cópia gerada por `scripts/sync-shared.sh`: não edite.

## Comandos

| Tarefa | Comando |
| --- | --- |
| Lint / typecheck / unitários | `make lint typecheck test` |
| Integração (Testcontainers: Postgres com as migrations reais + MiniStack) | `make test-integration` |
| Um teste | `./mvnw test -Dtest=OrderControllerTest#createReturns202WithLocation` |
| Subir só este serviço | `make run` (ou, na raiz, `docker compose up -d --build order-service`) |

Testes: matriz da plataforma P01–P21 (`PlatformCoreTest`, `PlatformIT` com a retenção em p21, `PeriodicJobRunnerTest`, logger com `"duplicate":true`) e `OrdersIT` (idempotência concorrente, RLS/BOLA, cada guarda da saga, scanner concorrente, corrida scanner × aprovação tardia, estorno único, consumo via SQS).
