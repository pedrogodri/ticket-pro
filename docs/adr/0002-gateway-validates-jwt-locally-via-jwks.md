# ADR-0002: Gateway valida JWT localmente via JWKS

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Pedro, Felipe
- **Tags**: segurança, gateway

## Contexto e problema

O gateway é o único serviço exposto ao cliente. Ele precisa autenticar toda requisição não pública sem transformar o auth-service num ponto único de latência e falha.

## Direcionadores

- Não depender do auth a cada requisição.
- Poder rotacionar a chave sem redeploy.
- Os serviços internos não podem confiar em identidade vinda do cliente.

## Opções consideradas

- Validação local com assinatura assimétrica (RS256/ES256) e JWKS em cache
- Introspecção remota no auth a cada request
- Segredo HMAC compartilhado entre auth e gateway

## Decisão

Escolhemos **validação local com JWKS**. O auth assina access tokens (15 min) com chave privada guardada no **Secrets Manager** e expõe `GET /.well-known/jwks.json` (somente rede interna). O gateway mantém o JWKS em cache (`JWKS_CACHE_TTL_S`) e o recarrega quando aparece um `kid` desconhecido. O gateway:

- **descarta** qualquer `X-User-Id` / `X-Correlation-Id` enviado pelo cliente e injeta os seus;
- libera sem token só `POST /auth/register`, `/auth/login` e `/auth/refresh`, numa lista explícita;
- consulta a blacklist de `jti` na **sua própria** cópia em Redis (ADR-0014).

### Consequências positivas

- O auth fora do ar não derruba requisições com token válido.
- A rotação de chave acontece por `kid`, sem redeploy.

### Consequências negativas

- Um token revogado só é bloqueado depois que o `TokenRevoked` chega ao gateway (segundos). Uma credencial vazada vale no máximo 15 min, mesmo que o evento se perca.
- Os serviços internos confiam no `X-User-Id`. Essa confiança depende do isolamento de rede (ADR-0004).

## Prós e contras

### JWKS local ✅
- ✅ Sem chamada por request, com rotação por `kid`.
- ❌ Revogação eventual.

### Introspecção remota
- ✅ Revogação imediata.
- ❌ Acopla a disponibilidade do sistema à do auth e acrescenta um hop por request.

### HMAC compartilhado
- ✅ Simples.
- ❌ O segredo fica em dois serviços, e qualquer um que o tenha pode emitir tokens.

## Links

- RFC-002, ADR-0014
