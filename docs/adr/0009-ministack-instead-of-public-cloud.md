# ADR-0009: MiniStack (AWS emulada) em vez de nuvem pública

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Grupo
- **Tags**: infraestrutura, nuvem

## Contexto e problema

O enunciado aceita MiniStack local ou nuvem pública. Tudo precisa subir com `docker compose up`, e o código fica público em 02/12.

## Opções consideradas

- MiniStack (`ministackorg/ministack`) local
- AWS real (free tier)
- LocalStack Community

## Decisão

Escolhemos o **MiniStack**, acessado por `AWS_ENDPOINT_URL=http://ministack:4566`, com credenciais fictícias por env e health check em `/_ministack/health`. O código usa os SDKs AWS oficiais com endpoint configurável, de modo que, em tese, basta trocar variáveis de ambiente para apontar para a AWS real.

Conferimos o suporte: SNS (fan-out para SQS e filter policy), SQS (DLQ), S3, Secrets Manager e KMS são emulados.

### Consequências positivas

- Custo zero, sem conta nem credencial real em repositório público.
- A demo funciona offline e é reproduzível na máquina do professor.

### Consequências negativas

- O estado de SNS/SQS fica só em memória: reiniciar o MiniStack exige reaplicar o Terraform. O `infra-init` roda a cada `up`, e o F6 usa `docker pause`.
- Pode haver divergências de comportamento em relação à AWS. Dois pontos ainda não estão confirmados: raw message delivery e o atributo `ApproximateNumberOfMessages`. São validados em spike na fase 1 do roadmap.
- É um projeto menos conhecido que o LocalStack. Se ele falhar, o fallback é o LocalStack, que usa a mesma porta e APIs compatíveis.

## Prós e contras

### MiniStack ✅
- ✅ Gratuito, leve, imagem única e compatível com LocalStack.
- ❌ Maturidade menor.

### AWS real
- ✅ Comportamento fiel.
- ❌ Custo, credenciais, e não sobe com `docker compose up`.

### LocalStack Community
- ✅ Maduro.
- ❌ Restrições recentes de licença/recursos na edição gratuita. Fica como plano B.

## Links

- ADR-0010, ADR-0011
