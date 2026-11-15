# ADR-0014: Revogação de token propagada por evento ao gateway

- **Data**: 2026-11-12
- **Status**: Aceito
- **Decisores**: Pedro, Felipe
- **Tags**: segurança, fronteiras

## Contexto e problema

O auth mantém a blacklist de `jti` no seu Redis (logout, compensação de cadastro). O gateway valida tokens localmente (ADR-0002) e precisa barrar tokens revogados. Ler o Redis do auth violaria o banco por serviço (ADR-0004), e chamar o auth a cada request anularia o ADR-0002.

## Opções consideradas

- O auth publica `TokenRevoked`, e o gateway mantém uma cópia no seu próprio Redis
- O gateway lê o Redis do auth
- Introspecção no auth para cada request
- Não revogar access tokens (só refresh) e confiar no TTL de 15 min

## Decisão

O auth grava o `jti` na sua blacklist e publica **`TokenRevoked { jti, userId, expiresAt, reason }`** via outbox. O gateway consome da fila `ticketpro-gateway` e grava `revoked:{jti}` no **Redis do gateway** (o mesmo do rate limit) com TTL = `expiresAt - now`. Em toda requisição autenticada, o gateway faz `EXISTS revoked:{jti}`.

Se o Redis do gateway estiver fora, a checagem de revogação é **fail-open com log de alerta**, e o rate limit também é fail-open. A justificativa é que a exposição máxima é o TTL de 15 min do access token e que fail-closed derrubaria todo o sistema por falha de cache.

### Consequências positivas

- Respeita o banco por serviço e mantém o gateway sem chamadas síncronas ao auth.
- O TTL faz a cópia se limpar sozinha.

### Consequências negativas

- A revogação é eventual (latência do outbox + fila, normalmente < 1 s).
- O gateway, apesar de "sem banco de negócio", passa a ter estado efêmero e a consumir uma fila. Isso deve ser declarado na apresentação.
- Fail-open é uma escolha consciente de disponibilidade sobre segurança, limitada a 15 min.

## Links

- ADR-0002, ADR-0004, RFC-002
