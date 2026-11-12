# ADR-0011: Infraestrutura como código com Terraform aplicado por container de init

- **Data**: 2026-11-12
- **Status**: Aceito — "state efêmero" substituído por [ADR-0019](0019-persistent-terraform-state-volume.md)
- **Decisores**: Grupo
- **Tags**: IaC, bônus

## Contexto e problema

Filas, tópicos, assinaturas, DLQs, buckets e segredos precisam ser criados por script de init, nunca à mão, dentro do `docker compose up`. O IaC também é bônus.

## Opções consideradas

- Terraform (provider `hashicorp/aws` com `endpoints` apontando para o MiniStack) num container `infra-init`
- Script shell com AWS CLI em `ready.d` do MiniStack
- Pulumi / CDK

## Decisão

Usamos **Terraform** em `infra/terraform/`, aplicado por um serviço `infra-init` (imagem `hashicorp/terraform` com versão fixada). Ele:

1. espera o MiniStack ficar saudável (`depends_on: condition: service_healthy`);
2. roda `terraform init && terraform apply -auto-approve` com provider configurado por env (`AWS_ENDPOINT_URL`, `skip_credentials_validation`, `skip_requesting_account_id`, `s3_use_path_style`);
3. termina com sucesso. Os serviços dependem dele com `condition: service_completed_successfully`.

Os módulos são `messaging` (tópico por domínio, fila + DLQ por consumidor, assinaturas com filter policy), `storage` (bucket) e `secrets` (chave JWT). Os seeds de catálogo **não** fazem parte do Terraform: são migrations de dados do inventory.

### Consequências positivas

- Os recursos são declarativos, versionados e revisáveis em PR.
- O mesmo código serviria para a AWS real, trocando só o provider/env.

### Consequências negativas

- O MiniStack não persiste SNS/SQS. Como o state do Terraform pode ficar "à frente" do MiniStack depois de um restart, o `infra-init` usa state **efêmero** (sem volume) e recria tudo a cada `up`.
- A compatibilidade de todos os recursos com o MiniStack ainda não foi verificada na prática. Se algum recurso falhar, o fallback é um script AWS CLI para aquele recurso, documentado no ADR que o substituir.
- Primeiro boot mais lento (download do provider). Mitigação: cache do diretório de plugins em volume.

## Prós e contras

### Terraform ✅
- ✅ Padrão de mercado e bônus IaC claro.
- ❌ Compatibilidade com o emulador a validar.

### Shell + AWS CLI
- ✅ Simples e garantido.
- ❌ Imperativo, e não conta como IaC.

### Pulumi/CDK
- ❌ Exige runtime extra e o CDK depende de CloudFormation.

## Links

- ADR-0009, ADR-0010
