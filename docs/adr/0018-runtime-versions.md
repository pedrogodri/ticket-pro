# ADR-0018: Versões de runtime dos serviços

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Grupo
- **Tags**: linguagens, plataforma
- **Substitui parcialmente**: [ADR-0008](0008-language-per-service.md) (somente a versão do .NET)

## Contexto e problema

O ADR-0008 fixou ".NET 8" para payment-service e user-service. O suporte do .NET 8 terminou em **10/11/2026**, dois dias antes do início da implementação. Também faltava fixar as versões das outras linguagens, que os templates da F0 precisam.

## Decisão

| Linguagem | Versão | Imagem de build → final |
|---|---|---|
| Go | 1.25 (`go 1.25` no `go.mod`) | `golang:1.25-alpine` → `gcr.io/distroless/static-debian12:nonroot` |
| Java | 21 LTS + Spring Boot 4.0 | `maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-alpine` |
| C# | **.NET 10 LTS** | `mcr.microsoft.com/dotnet/sdk:10.0` → `mcr.microsoft.com/dotnet/aspnet:10.0-alpine` |
| TypeScript | Node 22 LTS | `node:22-alpine` → `node:22-alpine` |

As versões de patch são as mais recentes de cada linha no momento do build e as imagens são fixadas por tag de versão (nunca `latest`).

### Consequências positivas

- Todos os runtimes ficam em linha suportada durante e depois da entrega (02/12).
- Os templates da F0 e os workflows de CI usam as mesmas versões.

### Consequências negativas

- O .NET 10 muda alguns padrões em relação ao .NET 8, como a imagem `-alpine` e o analisador mais estrito. O grupo de C# precisa usar a documentação do .NET 10.

## Links

- ADR-0008, [docs/padroes.md §2](../padroes.md)
