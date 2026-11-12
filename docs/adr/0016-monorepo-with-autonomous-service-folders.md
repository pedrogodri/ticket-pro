# ADR-0016: Monorepo com pastas de serviço autônomas (como repositórios separados)

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Grupo
- **Tags**: organização, repositório

## Contexto e problema

O professor precisa avaliar tudo num só lugar e subir com um único `docker compose up`. Ao mesmo tempo, o grupo quer que cada serviço seja independente como seria num repositório próprio: build, testes, dependências, migrations e documentação sem depender de nada fora da sua pasta. Isso deixa explícito o desacoplamento que o trabalho cobra.

## Opções consideradas

- Monorepo com pastas autônomas (polyrepo-style)
- Monorepo integrado (workspaces, libs importadas da raiz)
- Um repositório por serviço + repositório de orquestração

## Decisão

Um único repositório, em que **cada pasta de primeiro nível em `services/` é tratada como um repositório**:

- **Build isolado:** o contexto Docker é a própria pasta (`services/<svc>/`). É proibido `../` em imports, Dockerfile ou build files.
- **Dependências próprias:** manifesto + lockfile próprios (`go.mod`/`go.sum`, `pom.xml`, `*.csproj` + `packages.lock.json`, `package.json` + `package-lock.json`). Não há workspace na raiz.
- **Tudo do serviço mora nele:** `README.md`, `Dockerfile`, `.dockerignore`, `.env.example`, `compose.yaml` (o próprio serviço + o seu banco), migrations, testes e a cópia dos contratos e da lib de plataforma que usa (ADR-0017).
- **CI por serviço:** `.github/workflows/<svc>.yml` com filtro de caminho `services/<svc>/**`.
- **Raiz = orquestração e coisas transversais:** `docker-compose.yml` (inclui os `compose.yaml` dos serviços via `include:` + infraestrutura), `infra/`, `contracts/`, `shared/`, `scripts/` (cenários F1–F8, sincronização) e `docs/`.
- **Commits** com escopo do serviço (`feat(order-service): ...`), para que o histórico de cada pasta seja legível como o de um repo.

O teste de autonomia é que `cp -r services/<svc> /tmp/x && cd /tmp/x && docker build .` funcione e que os testes do serviço passem.

### Consequências positivas

- A avaliação acontece num lugar só, e a independência dos serviços é verificável.
- A divisão de trabalho por integrante fica natural: cada um é dono de uma pasta.
- Extrair um serviço para um repo próprio é copiar a pasta.

### Consequências negativas

- Há código de plataforma e contratos duplicados nas pastas, controlados por sincronização (ADR-0017).
- O `docker-compose.yml` da raiz depende de `include:` (Compose ≥ 2.20).
- 7 pipelines de CI em vez de 1.

## Prós e contras

### Pastas autônomas ✅
- ✅ Avaliação simples e independência real.
- ❌ Duplicação controlada.

### Monorepo integrado
- ✅ Zero duplicação.
- ❌ Os serviços só compilam dentro do monorepo, o que esconde acoplamento.

### Multi-repo
- ✅ Independência máxima.
- ❌ Avaliação e `docker compose up` ficam mais difíceis para o professor.

## Links

- ADR-0017, [docs/padroes.md §1](../padroes.md)
