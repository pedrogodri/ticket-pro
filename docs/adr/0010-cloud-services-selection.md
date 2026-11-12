# ADR-0010: Serviços de nuvem utilizados e justificativa

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Grupo
- **Tags**: nuvem

## Contexto e problema

O enunciado exige ≥3 categorias de serviço de nuvem, sendo mensageria obrigatória. Cada serviço precisa ter função real no fluxo, e não ser decorativo.

## Decisão

| Categoria | Serviço | Uso real | Quem usa |
|---|---|---|---|
| Mensageria | **SNS + SQS** (+ DLQ) | Todos os eventos de domínio (ADR-0001) | todos |
| Armazenamento de objetos | **S3** (`ticketpro-receipts`) | Comprovante JSON do pedido confirmado/cancelado (`receipts/{orderId}.json`) | notification |
| Segurança / segredos | **Secrets Manager** (`ticketpro/auth/jwt-signing-key`) | Par de chaves de assinatura JWT (PEM + `kid`), lido no boot do auth | auth |

Avaliados e **não** adotados:

- **DynamoDB:** cobriria os read models, mas o Postgres por serviço já atende e manter uma única tecnologia de banco reduz o risco no prazo.
- **ElastiCache:** o MiniStack sobe Redis real via Docker socket, mas o Redis em container direto é mais simples e equivalente para a demo.
- **CloudWatch:** a observabilidade fica com OpenTelemetry/Jaeger (ADR-0012).

### Consequências positivas

- As três categorias aparecem no fluxo principal e são demonstráveis: listar objetos no bucket e ler o segredo.
- Todos os nomes vêm de variáveis de ambiente (`RECEIPTS_BUCKET`, `JWT_SECRET_ID`, `*_TOPIC_ARN`, `*_QUEUE_URL`).

### Consequências negativas

- O auth depende do Secrets Manager no boot e não sobe sem ele. O `depends_on: infra-init (completed_successfully)` garante a ordem.
- A geração da chave JWT acontece no init: o Terraform cria o segredo com chave gerada por `tls_private_key`. Isso fica no state local do `infra-init`, aceitável para o ambiente emulado.

## Links

- ADR-0001, ADR-0009, ADR-0011
