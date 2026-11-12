# ADR-0004: Banco por serviço com isolamento de rede

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Grupo
- **Tags**: dados, fronteiras

## Contexto e problema

O enunciado exige banco por serviço e proíbe acesso a banco alheio. Precisamos garantir isso de forma **verificável**, e não só por convenção.

## Opções consideradas

- Um container Postgres por serviço, numa rede Docker exclusiva
- Um Postgres compartilhado com um schema/usuário por serviço
- Um banco compartilhado

## Decisão

Cada serviço tem **seu próprio container** de banco (`auth-db`, `users-db`, `orders-db`, `inventory-db`, `payments-db`, `notifications-db`, todos PostgreSQL 16), mais Redis próprio para gateway e auth. Cada banco fica numa rede `<svc>-data` da qual só o seu serviço participa. Dados de outro domínio chegam **apenas por eventos**: por exemplo, "meus ingressos" no user-service é projeção de `TicketsIssued`. As migrations são versionadas por serviço (`docs/padroes.md`).

### Consequências positivas

- Violar a regra é fisicamente impossível: o host do banco alheio nem resolve.
- Cada serviço evolui o schema de forma independente.

### Consequências negativas

- São ~8 containers de dados, o que aumenta o consumo de memória na máquina da demo. A mitigação é usar imagens `alpine` e `shared_buffers` reduzido.
- Não existem joins entre domínios, então as consultas cruzadas exigem read models (CQRS).

## Prós e contras

### Container por serviço ✅
- ✅ Isolamento real e demonstrável.
- ❌ Mais recursos.

### Schema por serviço num Postgres
- ✅ Leve.
- ❌ O isolamento depende de GRANTs, e um erro de config expõe tudo. Também é discutível perante o enunciado.

### Banco compartilhado
- ❌ Viola o requisito.

## Links

- ADR-0002 (confiança no `X-User-Id` depende do isolamento)
