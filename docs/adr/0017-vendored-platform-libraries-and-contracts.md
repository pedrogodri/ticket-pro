# ADR-0017: Libs de plataforma e contratos copiados (vendorizados) em cada serviço

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Grupo
- **Tags**: código compartilhado, repositório
- **Substitui**: [ADR-0013](0013-shared-libraries-per-language.md)

## Contexto e problema

O ADR-0013 previa `shared/go` e `shared/dotnet` importados direto pelos serviços (`replace`/`ProjectReference`). Com o ADR-0016, cada serviço precisa compilar **sozinho**, a partir da própria pasta. Os serviços também precisam dos schemas de `contracts/` para os testes de contrato.

## Opções consideradas

- Cópia versionada sincronizada por script (vendoring)
- Sem lib compartilhada (cada serviço implementa)
- Pacote publicado (módulo Go com tag + NuGet)

## Decisão

As fontes da verdade continuam sendo **`shared/go/`**, **`shared/dotnet/`** e **`contracts/`**, cada um com arquivo `VERSION` (semver). O script `scripts/sync-shared.sh` copia esse material para dentro de cada serviço:

| Origem | Destino no serviço | Ajuste na cópia |
|---|---|---|
| `shared/go/<pkg>` | `services/<svc>/internal/platform/<pkg>` | Reescreve o import path do módulo para `<módulo do serviço>/internal/platform/...` |
| `shared/dotnet/src/**.cs` | `services/<svc>/src/Platform/` | Nenhum (namespace `TicketPro.Platform`) |
| `contracts/**` relevantes | `services/<svc>/contracts/` | Só os schemas em que o serviço aparece em `x-producer`/`x-consumers` + `envelope`/`common` |

Cada destino recebe `.vendored.json`, com `{source, version, sha256}`, e todo arquivo copiado ganha um cabeçalho dizendo "NÃO EDITE: copiado de `<origem>@<versão>`; altere a origem e rode `scripts/sync-shared.sh`". O `sync-shared.sh --check` (CI da raiz) falha se alguma cópia divergir da origem na versão declarada.

Java (auth) e TypeScript (order) não têm lib compartilhada (ADR-0008), mas recebem a cópia dos **contratos** pelo mesmo script.

O escopo da lib continua o do ADR-0013: `envelope`, `messaging`, `outbox`, `idempotency`, `retry`, `logging`.

### Consequências positivas

- O serviço compila e testa a partir da própria pasta (ADR-0016).
- R1/R6 continuam escritos e testados uma vez por linguagem, em `shared/`.
- A versão em uso por serviço fica explícita e auditável.

### Consequências negativas

- O código duplicado no repo pode ser editado na cópia por engano. Mitigação: o cabeçalho e o `--check` na CI.
- Uma mudança na lib exige rodar o sync e commitar as cópias, gerando diffs maiores. Regra: um commit `chore(shared): sync vX.Y.Z` separado.
- A reescrita de import path em Go é feita com `sed`/`gofmt -r`, e precisa de teste no próprio script.

## Prós e contras

### Cópia versionada ✅
- ✅ Autonomia e código único.
- ❌ Sincronização obrigatória.

### Sem lib
- ✅ Nada para sincronizar.
- ❌ Infra crítica reescrita 3× em Go e 2× em C#.

### Pacote publicado
- ✅ O modelo mais fiel a repos separados.
- ❌ Exige repo público ou credenciais no `docker build` antes de 02/12.

## Links

- ADR-0013 (substituído), ADR-0016, [RFC 0001](../rfc/0001-message-contract-and-event-versioning.md)
