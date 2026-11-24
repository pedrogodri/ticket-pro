# Architecture Decision Records

Formato: [MADR](https://adr.github.io/madr/). ADRs são imutáveis: para mudar uma decisão, crie um novo ADR que a substitua (`Superseded by`).

| # | Decisão | Status |
|---|---|---|
| [0001](0001-sns-sqs-fanout-as-message-broker.md) | SNS + SQS (fan-out) como broker, tópico por domínio | Aceito |
| [0002](0002-gateway-validates-jwt-locally-via-jwks.md) | Gateway valida JWT localmente via JWKS | Aceito |
| [0003](0003-orchestrated-saga-in-order-service.md) | Saga orquestrada pelo order-service | Aceito |
| [0004](0004-database-per-service.md) | Banco por serviço com isolamento de rede | Aceito |
| [0005](0005-transactional-outbox.md) | Transactional outbox com relay por polling | Aceito |
| [0006](0006-idempotent-consumers-processed-messages.md) | Consumidor idempotente com `processed_messages` | Aceito |
| [0007](0007-conditional-update-prevents-overselling.md) | `UPDATE` condicional contra venda dupla | Aceito |
| [0008](0008-language-per-service.md) | Linguagem por serviço | Aceito (versão .NET em 0018; order e inventory em 0021) |
| [0009](0009-ministack-instead-of-public-cloud.md) | MiniStack em vez de nuvem pública | Aceito |
| [0010](0010-cloud-services-selection.md) | Serviços de nuvem: SNS/SQS, S3, Secrets Manager | Aceito |
| [0011](0011-terraform-applied-by-init-container.md) | Terraform aplicado por container de init | Aceito (state em 0019) |
| [0012](0012-opentelemetry-tracing.md) | OpenTelemetry com Collector + Jaeger | Aceito |
| [0013](0013-shared-libraries-per-language.md) | Biblioteca interna por linguagem (`shared/go`, `shared/dotnet`) | Substituído por 0017 |
| [0014](0014-token-revocation-via-event.md) | Revogação de token propagada por evento ao gateway | Aceito |
| [0015](0015-queue-based-autoscaler.md) | Autoescala por profundidade de fila via Docker API | Aceito |
| [0016](0016-monorepo-with-autonomous-service-folders.md) | Monorepo com pastas de serviço autônomas | Aceito |
| [0017](0017-vendored-platform-libraries-and-contracts.md) | Libs de plataforma e contratos vendorizados em cada serviço | Aceito |
| [0018](0018-runtime-versions.md) | Versões de runtime (Go 1.25, Java 21, .NET 10, Node 22) | Aceito |
| [0019](0019-persistent-terraform-state-volume.md) | State do Terraform persistido em volume | Aceito |
| [0020](0020-database-roles-and-data-networks.md) | Roles de banco owner/app/worker e rede de dados isolada por serviço | Aceito |
| [0021](0021-order-and-inventory-in-java.md) | order-service e inventory-service em Java | Aceito |
| [0022](0022-java-platform-copied-from-auth-with-drift-check.md) | Plataforma Java copiada do auth-service, com checagem de deriva | Aceito |
| [0023](0023-internal-architecture-chosen-per-service.md) | Arquitetura interna escolhida por serviço, sem princípio comum | Aceito |
