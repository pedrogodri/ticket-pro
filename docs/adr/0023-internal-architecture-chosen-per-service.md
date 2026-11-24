# ADR-0023: Arquitetura interna escolhida por serviço, sem princípio comum

- **Data**: 2026-11-24
- **Status**: Aceito (2026-11-24)
- **Decisores**: Pedro (pedido de 24/11); vale para todos os donos de serviço
- **Tags**: estrutura de código, organização, autonomia
- **Relacionado**: [ADR-0008](0008-language-per-service.md), [ADR-0016](0016-monorepo-with-autonomous-service-folders.md), [ADR-0017](0017-vendored-platform-libraries-and-contracts.md), [ADR-0022](0022-java-platform-copied-from-auth-with-drift-check.md), [`padroes.md` §2](../padroes.md)

## Contexto e problema

O [`padroes.md` §2](../padroes.md) impunha a todos os serviços a mesma organização interna: *feature-first*, com `modules/<feature>/{entrada, application, domain, infrastructure}` e a plataforma à parte, nas três linguagens. A regra não estava num ADR, só no guia de padrões.

Os oito serviços são bem diferentes entre si. O gateway é um proxy com middlewares, o autoscaler é um laço pequeno, o payment só tem eventos e um worker, o order orquestra uma saga, e o user é um read model. As três linguagens também têm costumes próprios: em .NET são comuns as soluções em camadas (Clean Architecture, com projetos `Domain`, `Application` e `Infrastructure`); em Go, a comunidade organiza pacotes por funcionalidade e evita pacotes genéricos como `models` e `controllers`; no Spring, convivem *package by layer*, *package by feature* e hexagonal. Uma forma única serve bem a alguns desses casos e mal a outros: num serviço de um módulo só, por exemplo, ela só acrescenta um nível de pasta.

## Direcionadores

- A autonomia que importa entre microsserviços é de deploy, dados e contrato (ADR-0004, ADR-0016), não de pastas.
- Cada dono conhece o seu serviço e a sua linguagem melhor que uma regra geral.
- O que os outros serviços enxergam precisa continuar igual: mensagens, comportamento sob falha, logs, health e configuração.
- As ferramentas do repositório (`sync-shared.sh`, `check-java-platform.sh`, `check-autonomy.sh`, CI) dependem de alguns caminhos fixos.

## Opções consideradas

- **A. Princípio comum para todos** (status quo: *feature-first* com camadas dentro).
- **B. Cada serviço escolhe a arquitetura interna que for melhor para ele e para a sua linguagem.**
- **C. Uma arquitetura por linguagem** (por exemplo, camadas em C#, hexagonal em Java, pacote por funcionalidade em Go).

## Decisão

Escolhemos a **opção B**. Não há princípio comum de organização interna: o dono de cada serviço escolhe a arquitetura que serve melhor ao serviço e à linguagem (camadas, Clean Architecture, hexagonal, *vertical slices*, pacote por funcionalidade ou outra) e registra a escolha, com o motivo, no README do serviço (seção **"Arquitetura"** ou **"Código"**).

Continua obrigatório, porque não é arquitetura interna:

- **Contrato externo:** envelope e `contracts/` (RFC 0001), publicação só pelo outbox, consumo com `processed_messages` e ack depois do commit (RFC 0003 §5.4), logs JSON com os campos do `padroes.md` §5, `/health/live` e `/health/ready`, configuração por env validada no boot, erros `application/problem+json`, identidade só pelo `X-User-Id` do gateway.
- **Autonomia:** cada `services/<svc>` compila e sobe sozinho (ADR-0016).
- **Caminhos que as ferramentas leem:** `Makefile` com os alvos do §1.2, `Dockerfile`, `compose.yaml`, `.env.example`; `migrations/` em quem tem banco e `contracts/` em quem produz ou consome eventos; a plataforma vendorizada em `internal/platform/` (Go) e `src/<Projeto>/Platform/` (C#), sem edição (ADR-0017); em Java, o pacote `br.furb.ticketpro.<svc>.platform` comparado pelo `check-java-platform.sh` (ADR-0022).

Os serviços que já existem mantêm a estrutura atual, que é uma escolha válida. Mudá-la é decisão do dono, num PR próprio.

### Consequências positivas

- Cada serviço usa a forma mais natural para o seu tamanho, o seu papel e a sua linguagem.
- Nenhum serviço pequeno carrega níveis de pasta que não usa.
- A decisão fica com quem mantém o código, e o motivo fica escrito no README.

### Consequências negativas

- Quem revisa um serviço de outro dono, às vezes em outra linguagem, precisa primeiro ler a seção do README que descreve a estrutura dele.
- Não há mais uma resposta única para "onde fica a regra de negócio"; a uniformidade na apresentação passa a vir do contrato externo, não das pastas.
- Os três serviços Java compartilham a mesma `platform/` (ADR-0022), mas podem organizar o resto de formas diferentes.

## Prós e contras

### A. Princípio comum
- ✅ Quem conhece um serviço se acha em todos.
- ❌ Força a mesma forma em serviços de tamanho e papel muito diferentes, e contra o costume de algumas linguagens.

### B. Livre por serviço ✅
- ✅ Melhor encaixe por serviço; decisão com o dono.
- ❌ Menos previsibilidade entre serviços; depende do README para orientar quem chega.

### C. Uma arquitetura por linguagem
- ✅ Respeita o costume de cada linguagem.
- ❌ Continua ignorando as diferenças entre serviços da mesma linguagem, como o gateway e o autoscaler, ou o order e o auth.

## Links

- [`padroes.md` §2](../padroes.md), que passa a apontar para este ADR
- ADR-0004, ADR-0008, ADR-0016, ADR-0017, ADR-0022; [RFC 0001](../rfc/0001-message-contract-and-event-versioning.md), [RFC 0003](../rfc/0003-failure-handling-strategy.md)
