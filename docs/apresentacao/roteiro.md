# Roteiro de apresentação — Ticket Pro

Deck: https://canva.link/0yrqqhgus5sqy8b (26 slides). Tempo: ~26 min de fala + demo. Ajuste conforme o tempo que o professor der.

**Divisão sugerida de falas** (cada um fala do que implementou):
- **Matheus:** abertura, saga, estados, compensação, método
- **Pedro:** arquitetura, gateway, mensageria, escala, observabilidade
- **Felipe:** segurança/auth, zero venda dupla
- **Gabriel:** dependência instável (payment)
- **Yasmin:** banco por serviço, CQRS
- **Todos:** demo e conclusão

Legenda: **Na tela** = texto do slide. **Imagem** = o que colocar de visual. **Fala** = texto para dizer (adapte com suas palavras, não leia).

---

## Bloco 1: Abertura e tema

### Slide 1: Capa (Matheus, 0:30)
- **Na tela:** Ticket Pro · Backend distribuído de venda de ingressos · Sistemas Distribuídos, FURB 2026 · nomes dos 5.
- **Imagem:** logo/ícone simples de ingresso; nada além.
- **Fala:** "Boa tarde. Somos o grupo do Ticket Pro: Pedro, Felipe, Gabriel, Yasmin e eu, Matheus. Vamos apresentar um backend distribuído de venda de ingressos, o que decidimos, por que decidimos, e depois mostrar funcionando, inclusive quebrando."

### Slide 2: O tema e o problema (Matheus, 1:00)
- **Na tela:** "Um backend. Um problema crítico." · 3 cartões: Setor (estoque compartilhado), Quantidade (concorrência real), Backend only (contratos + eventos).
- **Imagem:** ilustração de uma fila de pessoas/ingressos, ou ícone de ingresso com cadeado. Opcional.
- **Fala:** "O Ticket Pro vende ingressos por setor e quantidade, estilo Ticketmaster, somente backend. Escolhemos porque é um problema naturalmente distribuído: muita gente disputando o mesmo estoque, um pagamento lento e instável no meio, e falhas parciais entre serviços. O nosso foco não é throughput, é corretude: nunca vender o mesmo ingresso duas vezes, nem sob concorrência, nem sob falha."

### Slide 3: O contrato da banca (Matheus, 1:00)
- **Na tela:** R1→R6 (requisitos obrigatórios) · F1→F8 (falhas reproduzíveis) · Bônus.
- **Imagem:** nenhuma. Sugestão: trocar os 3 cartões por 3 colunas com os itens (abaixo).
- **Texto de apoio para o slide:**
  - Obrigatórios: saga com compensação · dependência instável · evento com 2+ consumidores
  - R1–R6: idempotência, zero venda dupla, API assíncrona, consumidores concorrentes, dependência por env, reconexão
  - Bônus: OpenTelemetry, IaC, autoescala, CQRS, versionamento de evento
- **Fala:** "O enunciado pede três elementos obrigatórios, seis requisitos técnicos e oito cenários de falha. Montamos o projeto de trás para frente: para cada requisito existe um teste ou um script que prova. Mais para o fim, vocês vão ver essa matriz requisito-evidência."

## Bloco 2: Visão da solução

### Slide 4: Arquitetura distribuída (Pedro, 1:30)
- **Na tela:** Cliente → API Gateway → 8 serviços → SNS→SQS → Postgres (um por serviço).
- **Imagem (importante):** diagrama de componentes de `docs/arquitetura.md` §2. Gerar a imagem: copiar o bloco Mermaid em mermaid.live, exportar PNG/SVG e colar no slide. Tela cheia, fundo escuro.
- **Fala:** "O cliente fala só com o gateway. Atrás dele, oito serviços em três linguagens se comunicam por eventos assíncronos, SNS publicando e SQS entregando. Cada serviço tem o próprio banco. Tudo sobe com um `docker compose up`, são cerca de 25 containers, e a AWS é emulada pelo MiniStack."

### Slide 5: 8 serviços, 8 responsabilidades (Pedro, 1:00)
- **Na tela:** gateway (entrada + roteamento) · auth (identidade) · user (perfil) · order (saga) · inventory (reservas) · payment (cobrança) · notification (comprovantes) · autoscaler (escala).
- **Correção:** trocar "notification: ingressos" por "notification: comprovantes".
- **Imagem:** um ícone por serviço, mesma família de ícones.
- **Fala:** "Cada serviço é dono de um dado. Auth cuida de credenciais; user do perfil; order orquestra a compra; inventory é dono do estoque; payment cobra; notification gera o comprovante; o autoscaler ajusta réplicas pela fila."

### Slide 6: Poliglota por contrato (Pedro, 1:00)
- **Na tela:** Go: gateway, notification, autoscaler (Pedro) · Java: auth, inventory (Felipe), order (Matheus) · C#: payment (Gabriel), user (Yasmin).
- **Imagem:** três logos (Go, Java, C#) com os serviços embaixo.
- **Fala:** "Três linguagens não é enfeite, é requisito. O que mantém isso viável é o contrato: todas as mensagens têm envelope e schema únicos, em JSON Schema e AsyncAPI. Qualquer serviço poderia ser reescrito em outra linguagem sem os outros perceberem."

### Slide 7: Uma única porta pública (Felipe, 1:00)
- **Na tela:** Internet → Gateway (JWT · rate limit) → Rede privada (7 serviços, 0 portas públicas).
- **Imagem:** o diagrama já está no slide. Complementar com 3 tópicos: JWT via JWKS · revogação por evento `TokenRevoked` · BOLA tratado como 404.
- **Fala:** "Só o gateway publica porta. Ele valida o JWT localmente, usando a chave pública do auth, e injeta o `X-User-Id`; os serviços confiam só nesse header, nunca no corpo da requisição. A revogação de token chega ao gateway por um evento `TokenRevoked`, e o token passa a dar 401 na hora; na demo mostramos isso publicando o evento. A chave de assinatura mora no Secrets Manager."
- **Atenção:** o auth-service não tem `/auth/logout` nem `/auth/refresh`. Não dizer "logout gera o evento"; se perguntarem, a revogação é demonstrada por `scripts/demo.sh gateway`.

## Bloco 3: Comunicação e dados

### Slide 8: Fan-out sem acoplamento (Pedro, 1:00)
- **Na tela:** SNS (tópico por domínio) → SQS + filter → consumidor → DLQ.
- **Imagem:** o diagrama do slide serve. Adicionar, ao lado, o envelope em formato código: `messageId · correlationId · type · version · occurredAt · payload`.
- **Fala:** "Um tópico SNS por domínio, e uma fila SQS por consumidor, com filtro pelo tipo da mensagem. Cada fila tem DLQ, depois de três tentativas a mensagem vai para lá. Toda mensagem usa o mesmo envelope, com versão, o que permite evoluir eventos sem quebrar consumidores."

### Slide 9: Mapa de eventos (Pedro, 0:45)
- **Na tela:** 15 eventos, três convergências: `TicketsIssued`, `OrderConfirmed`, `OrderCancelled`.
- **Imagem:** tabela de eventos de `docs/arquitetura.md` §7 (evento → publica → consome), como imagem ou tabela do Canva.
- **Fala:** "São 15 eventos. Três deles têm mais de um consumidor, o que cumpre o requisito: `TicketsIssued` vai para o user, que atualiza 'meus ingressos', e para o notification, que gera o comprovante."

### Slide 10: Dados privados por padrão (Yasmin, 1:00)
- **Na tela:** order→orders_db · inventory→inventory_db · payment→payment_db · user/auth→db próprio. "Sem JOIN entre domínios · redes isoladas."
- **Imagem:** diagrama simples com cada serviço ligado só ao seu banco; cadeado entre eles.
- **Fala:** "Cada serviço tem o seu Postgres, em rede própria: um serviço nem consegue resolver o host do banco do outro. Se precisa de dado alheio, recebe por evento. Um exemplo é o 'meus ingressos' do user-service: é um modelo de leitura, CQRS, alimentado pelos eventos de ingressos emitidos."

### Slide 11: A nuvem cabe no laboratório (Pedro, 1:00)
- **Na tela:** SNS/SQS (mensageria) · S3 (comprovantes) · Secrets Manager + IaC (chave JWT, Terraform).
- **Imagem:** screenshot do terminal rodando `scripts/verify-infra.sh | tail -1` (all checks passed), ou bucket S3 com o comprovante.
- **Fala:** "Usamos MiniStack, uma AWS emulada, então tudo roda na máquina. São três categorias de nuvem: mensageria, armazenamento de objetos e gerenciamento de segredos. Os recursos são criados por Terraform, num container de init que roda e termina."

## Bloco 4: Saga e confiabilidade

### Slide 12: A saga de compra (Matheus, 1:30)
- **Na tela:** POST /orders → 202 → Reservar estoque → Pagar → Confirmar → Emitir ingressos.
- **Imagem (importante):** diagrama de sequência de `docs/arquitetura.md` §6, exportado do Mermaid. Se ficar grande, usar só o caminho feliz.
- **Fala:** "O cliente faz POST e recebe 202 na hora, com o id do pedido. O fluxo continua em segundo plano: o order publica `OrderCreated`; o inventory reserva; o order pede o pagamento; o payment cobra; o order confirma; o inventory emite os ingressos. Quem orquestra é o order-service. O cliente acompanha com GET no pedido."

### Slide 13: Estado só avança (Matheus, 1:00)
- **Na tela:** PENDING → AWAITING_PAYMENT → CONFIRMED | CANCELLED · `UPDATE … WHERE status = estado_esperado`.
- **Imagem:** diagrama de estados de `docs/arquitetura.md` §6 (Mermaid stateDiagram).
- **Fala:** "Toda transição é um UPDATE condicional no estado esperado. Se chega evento duplicado ou fora de ordem, o UPDATE afeta zero linhas e nada regride. Esse é o truque que torna a saga segura sem lock distribuído."

### Slide 14: Cancelar cedo, corrigir tarde (Matheus, 1:00)
- **Na tela:** Falha/deadline → `OrderCancelled` · Compensação: estoque devolvido · Aprovação tardia: estorno automático · Invariante: TTL da reserva > deadline.
- **Imagem:** setas de compensação no diagrama da saga (reserva → devolução).
- **Fala:** "Se o pagamento falha ou estoura o prazo, cancelamos e o inventory devolve exatamente o que reservou. Se a aprovação chega tarde, depois do cancelamento, o pedido continua cancelado e pedimos estorno. E há uma invariante validada no boot: o tempo de reserva é sempre maior que o prazo de pagamento, para a confirmação nunca encontrar a reserva já expirada."

### Slide 15: Zero venda dupla (Felipe, 1:00)
- **Na tela:** `UPDATE sectors SET available = available - q WHERE available >= q` + `CHECK (available >= 0)` · Resultado: 10 reservados / 90 sem estoque.
- **Imagem:** screenshot do resultado de `scripts/f5-*.sh` (10 CONFIRMED, 90 OUT_OF_STOCK). Gerar antes da apresentação.
- **Fala:** "A corrida pelo estoque é decidida no banco, não na aplicação. O UPDATE só diminui se houver saldo, e uma constraint garante que nunca fica negativo. No teste, 100 pedidos para um setor de 10 ingressos: exatamente 10 reservados e 90 rejeitados."

### Slide 16: At-least-once + idempotência (Pedro, 1:30)
- **Na tela:** Produtor: transação (domínio + outbox) → relay (backoff) → broker · Consumidor: valida → transação → `processed_messages` → ack.
- **Imagem:** o fluxo do slide em duas linhas, uma de produtor e uma de consumidor, com ack só no fim.
- **Fala:** "O broker entrega pelo menos uma vez, então duplicata vai acontecer. Do lado do produtor, publicamos só pelo outbox, na mesma transação do efeito; um relay drena com backoff e se recupera sozinho se o broker cair. Do lado do consumidor, gravamos o id da mensagem na mesma transação do efeito e só então damos ack. Entrega duplicada, efeito único."

### Slide 17: Falhar é parte do fluxo (Gabriel, 1:00)
- **Na tela:** Latência + taxa de falha por variável de ambiente · Timeout + retry · SKIP LOCKED + lease, fora da transação · Poison message → DLQ.
- **Imagem:** trecho de config com `PAYMENT_LATENCY_MS`, `PAYMENT_FAILURE_RATE`, `PAYMENT_PROVIDER_TIMEOUT_MS`.
- **Fala:** "O provedor de pagamento é simulado e instável de propósito, com latência e taxa de falha configuráveis por variável de ambiente. O consumidor grava o pagamento como pendente e dá ack; um worker, com SKIP LOCKED e lease, chama o provedor depois, fora de transação. Assim a chamada lenta não segura a mensagem. Mensagem inválida vai para a DLQ sem travar a fila."

## Bloco 5: Escala, observabilidade e método

### Slide 18: Escala horizontal (Pedro, 1:00)
- **Na tela:** Fila (profundidade N) → order ×2, inventory ×2, payment ×2 · Autoscaler: profundidade → 3 réplicas.
- **Imagem:** screenshot de `docker compose ps` mostrando as réplicas.
- **Fala:** "Order, inventory e payment sobem com duas réplicas consumindo a mesma fila. Relay e jobs usam SKIP LOCKED, então várias réplicas não duplicam trabalho. O autoscaler lê a profundidade da fila e ajusta as réplicas pela API do Docker."

### Slide 19: Um trace atravessa três runtimes (Pedro, 1:00)
- **Na tela:** POST /orders → order → inventory → payment → TicketsIssued · Logs JSON com correlationId + traceId · OpenTelemetry → Collector → Jaeger · /live + /ready.
- **Imagem (importante):** screenshot do trace da saga no Jaeger (http://localhost:16686), com spans dos três runtimes visíveis. Gerar rodando `scripts/happy-path.sh`.
- **Fala:** "O `traceparent` viaja no header HTTP e nos atributos da mensagem, então um único trace cobre do POST até os ingressos emitidos, passando por Java, C# e Go. Os logs também carregam correlationId e traceId."

### Slide 20: Como trabalhamos (Matheus, 1:00)
- **Na tela:** Contrato primeiro · Spikes reduzem risco · Donos + review · Cenários como definição de pronto. Acrescentar: 22 ADRs · 12 RFCs · CI por serviço.
- **Imagem:** screenshot da lista de ADRs (`docs/adr/README.md`) ou do roadmap em fases.
- **Fala:** "Trabalhamos docs primeiro: 22 ADRs e 12 RFCs registram cada decisão e o porquê. Contratos viram a fonte da verdade. O roadmap foi em fases, cada uma com um checkpoint verificável por comando ou teste. Branch por tarefa, commits convencionais, CI por serviço."

### Slide 21: Decisões sob restrição (Matheus, 1:00)
- **Na tela:** MiniStack: spikes + planos B · Java: plataforma do auth + checagem de deriva · Order + inventory: troca de linguagem preservando contratos.
- **Imagem:** nenhuma. Opcional: screenshot do relatório do spike (`docs/spikes/2026-11-12-ministack.md`).
- **Fala:** "No começo, validamos o que o MiniStack suporta antes de apostar nele, com planos B. Também mudamos a linguagem de order e inventory no meio do caminho, e isso só foi possível porque o contrato não mudou."

## Bloco 6: Demo e fechamento

### Slide 22: Demonstração (todos, 5–8 min, ao vivo)
- **Na tela:** 1 Caminho feliz (202 → CONFIRMED, trace no Jaeger) · 2 O professor escolhe uma falha (previsão → execução → evidência) · 3 Falha surpresa: derrube qualquer serviço · 4 F5: 100 pedidos / 10 ingressos.
- **Imagem:** nenhuma; deixar o slide só como marcador e trocar para o terminal. Pedro opera o terminal; o dono do serviço explica o que aparece.
- **Roteiro da demo** (tudo pelo `scripts/demo.sh`, ver `docs/runbook.md`):

  | Passo | Comando | O que dizer |
  |---|---|---|
  | 1. Caminho feliz | `scripts/demo.sh happy` | 202 imediato, estados até CONFIRMED, tabelas dos 5 bancos; abrir o link do Jaeger impresso no fim (trace em Java, C# e Go). |
  | 2. Professor escolhe | `scripts/demo.sh` (menu) | "Qual falha o senhor quer ver?" O script mostra **como provocar** e o **resultado esperado** e espera o Enter: ler a previsão em voz alta antes de rodar, depois mostrar a evidência nos bancos e nos logs. |
  | 3. Falha surpresa | `scripts/demo.sh surprise <serviço> [s]` | "Pode escolher qualquer serviço." Mostra a previsão, derruba as réplicas, compra durante a queda, restaura e mostra como o pedido terminou. |
  | 4. Se sobrar tempo | `scripts/demo.sh f5` | Corrida: 10 passam, 90 `OUT_OF_STOCK`, estoque nunca negativo. |

- **Tempo dos cenários:** F4 2 s · F3 4 s · F8 12 s · F1 31 s · F2 33 s · F5 34 s · F7 44 s · F6 66 s. Com pouco tempo, preferir F4, F3, F8 e F1; durante o F6, explicar o outbox.
- **Variações sob pedido:** `PAYMENT_FAILURE_RATE=1 docker compose up -d --no-deps payment-service` (todo pagamento falha), `=0` (todo passa), `PAYMENT_LATENCY_MS=20000` (lento, como F7); depois `scripts/demo.sh order` mostra o pedido em todos os bancos. `surprise payment-service 25` passa do prazo de 15 s e mostra cancelamento + estorno.
- **Se algo der errado ao vivo:** o payment falha 10% das vezes por padrão; se uma compra cancelar sem querer, virar demo ("olha a compensação") com `scripts/demo.sh order`. Serviço fora depois da surpresa: `docker compose up -d && scripts/wait-healthy.sh`.
- **Fala:** "Primeiro o caminho feliz, depois o senhor escolhe o que quebrar."
- **Plano B:** gravar um vídeo curto da demo antes, para o caso de a stack não subir.

### Slide 23: F1 → F8 (Gabriel, 1:00)
- **Na tela:** F1 compensação · F2 kill de consumidor · F3 duplicata ×5 · F4 poison → DLQ · F5 corrida 100/10 · F6 broker off 30s · F7 resposta tardia · F8 escala ×3.
- **Imagem:** screenshot de `scripts/demo.sh all` com as 13 linhas ✔ e "demo all: 0 of 13 failed" (ensaio de 25/11: 13/13 em 6m49s).
- **Fala:** "Cada falha tem um script com verificação automática. Rodamos os oito, mais caminho feliz, réplicas, gateway, versionamento e autoescala, em sequência a partir de uma stack limpa: 13 de 13."

### Slide 24: Bônus (Pedro, 0:45)
- **Na tela:** Jaeger (trace ponta a ponta) · Eventos (`OrderCreated` v1 · v2 · v3) · Autoescala por fila · CQRS · IaC.
- **Imagem:** saída de `scripts/demo-versioning.sh` (v3 indo para a DLQ) ou do `demo-autoscale.sh`.
- **Fala:** "Cinco bônus. O de versionamento mostra o consumidor tolerante: v1 e v2 funcionam, e uma v3 desconhecida vai para a DLQ em vez de derrubar o serviço."

### Slide 25: Requisito → evidência → status (Matheus, 1:00)
- **Na tela:** tabela: R1–R6 · F1–F8 · Bônus, cada um com a evidência e o status real.
- **Imagem:** tabela de `docs/arquitetura.md` §11.
- **Status real (25/11):** todos os 8 serviços entregues. R1–R6, F1–F8 e os 5 bônus verdes (`scripts/demo.sh all` 13/13). Lacunas conhecidas, fora do enunciado: auth sem `/auth/refresh` e `/auth/logout`; inventory sem o job de expiração de reservas (`InventoryReservationExpired` nunca é publicado; nenhum F depende dele).
- **Fala:** "Esta é a matriz do que foi pedido e da evidência de cada item. Tudo tem teste ou script que prova, e rodamos tudo hoje: 13 de 13. O que ficou de fora: refresh e logout no auth, e a expiração automática de reservas. Nenhum requisito ou falha depende deles."

### Slide 26: Conclusão e perguntas (todos, 1:00)
- **Na tela:** Consistência eventual exige intenção explícita · Idempotência · compensação · observabilidade · Próximos passos: CDC · assentos numerados · fila de espera · KEDA · Repositório público em 02/12 · Obrigado. Perguntas?
- **Imagem:** QR code do repositório (gerar depois que for público).
- **Fala:** "O que levamos: consistência eventual não acontece por acidente, é uma decisão que se projeta; idempotência e compensação são o preço da comunicação assíncrona; e sem observabilidade nada disso é depurável. Se crescêssemos: CDC no lugar do polling do outbox, assentos numerados, fila de espera e KEDA. O repositório fica público em 2 de dezembro. Obrigado, abertos a perguntas."

---

## Preparação antes de 25/11

1. **Gerar imagens:** exportar os 3 diagramas Mermaid (componentes, sequência da saga, estados) em PNG/SVG; screenshots do Jaeger (slide 19), `docker compose ps` (18), resultado do F5 (15), `scripts/demo.sh all` (23).
2. **Ensaiar a demo** em máquina limpa, cronometrada (`git clone` → `docker compose up` → roteiro). Gravar vídeo de backup.
3. **Corrigir** slide 5 (notification = comprovantes) e slide 20 (citar ADRs/RFCs).

**15 min antes de entrar:** `docker compose down -v && docker compose up -d --build` → `scripts/wait-healthy.sh` → `scripts/demo.sh all` (aquece e confirma 13/13). Fonte do terminal grande; aba do Jaeger aberta em http://localhost:16686.

## Perguntas prováveis da banca

| Pergunta | Resposta curta |
|---|---|
| Por que orquestração e não coreografia? | Um lugar só concentra o estado e o deadline do pedido, o que facilita a compensação e a depuração (ADR-0003). |
| E se o broker cair? | O outbox acumula e o relay retoma com backoff; F6 demonstra com `docker pause ministack`. |
| Como garante que não vende em dobro com várias réplicas? | O `UPDATE` condicional e o `CHECK` decidem no banco; réplicas não coordenam nada (ADR-0007, F5). |
| E mensagem duplicada? | `processed_messages` na mesma transação do efeito (ADR-0006, F3). |
| Por que MiniStack? | Roda local e reproduzível, sem custo; validamos os recursos com spikes (ADR-0009). |
| O que o ack só depois do commit evita? | Perder a mensagem se o serviço cair entre o ack e a gravação (RFC 0003 §5.4). |
| Por que polling no outbox e não CDC? | Mais simples para o volume da demo; CDC fica como evolução (arquitetura §12). |
| O que falta implementar? | Refresh/logout no auth e o job de expiração de reservas no inventory; nenhum R ou F depende deles (slide 25). |
| Mostra o logout revogando o token? | Não há endpoint de logout; a revogação é por evento `TokenRevoked`, mostrada com `scripts/demo.sh gateway`. |
