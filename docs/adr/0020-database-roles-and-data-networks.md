# ADR-0020: Roles de banco owner/app/worker e rede de dados isolada por serviço

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Grupo
- **Tags**: banco de dados, segurança, infraestrutura

## Contexto e problema

O ADR-0004 dá um Postgres por serviço, e o padrões §6.1 define três roles: `<db>_owner` (Flyway), `<db>_app` (API, sujeita a RLS) e `<db>_worker` (consumidores, relay e jobs, com `BYPASSRLS`). Faltava decidir como esses roles nascem num `docker compose up` limpo, de onde vêm as senhas e como garantir que nenhum serviço alcance o banco de outro.

## Opções consideradas

- Roles criados por migration Flyway, com senhas por placeholder; banco numa rede `internal` própria
- Roles criados por script em `docker-entrypoint-initdb.d`
- Um único usuário por banco, sem separar API e workers

## Decisão

- **Owner:** é o `POSTGRES_USER` do container (`<db>_owner`). Na imagem oficial ele é superusuário. Isso é aceitável **só em desenvolvimento**, e é o que permite criar `<db>_worker` com `BYPASSRLS`.
- **`V0001__create_roles.sql`:** cria `<db>_app` (LOGIN, sem BYPASSRLS) e `<db>_worker` (LOGIN, BYPASSRLS) e define `ALTER DEFAULT PRIVILEGES` (DML em tabelas e uso de sequências para os dois). As senhas chegam pelos placeholders `${appPassword}`/`${workerPassword}` (`FLYWAY_PLACEHOLDERS_*`), definidos no `.env.example` do serviço e marcados "DEVELOPMENT ONLY".
- **Imagem do Flyway:** `flyway/flyway:13.11.1-alpine`, igual em todos os serviços, num job `<svc>-migrate` que roda antes do serviço (`service_completed_successfully`).
- **Rede de dados:** cada banco fica **só** na rede `ticketpro-<svc>-data`, com `internal: true`. O serviço e o seu migrate ficam em `ticketpro-backend` + `<svc>-data`. Nenhum outro serviço consegue sequer resolver o nome do banco alheio, e nenhum banco publica porta.

### Consequências positivas

- A separação de privilégios (RLS na API, BYPASSRLS só nos workers) existe desde a primeira migration, e o mesmo arquivo roda igual nas 4 linguagens.
- O isolamento do ADR-0004 vira garantia de rede, e não só convenção.
- Tudo é reproduzível a partir de `docker compose down -v`.

### Consequências negativas

- O owner superusuário não serve para produção. Lá ele seria um role com `CREATEROLE`, e o `BYPASSRLS` seria concedido por um administrador.
- As senhas de demonstração ficam versionadas no `.env.example` de cada serviço.
- Uma migration aplicada não pode ser editada, então trocar senha ou role exige uma nova migration.

## Prós e contras

### Migration Flyway + rede `internal` ✅
- ✅ Igual em todos os serviços, versionada e validada por checksum.
- ❌ Depende de placeholders, e o owner precisa ser superusuário em dev.

### Script em `docker-entrypoint-initdb.d`
- ✅ Não precisa de placeholders.
- ❌ Roda só na primeira inicialização do volume, fica fora do Flyway e não é testável do mesmo jeito.

### Usuário único
- ✅ Mais simples.
- ❌ Perde a defesa em profundidade contra BOLA (padrões §6.1, RFC 0002 §5.6).

## Links

- ADR-0004, [docs/padroes.md §6](../padroes.md), RFC 0002 §5.6
