# auth-service

Credenciais, emissão de JWT, refresh tokens e JWKS do Ticket Pro, em Java 21 com Spring Boot 4.0.

**Dono:** Felipe.

**Estado:** esqueleto. Hoje só tem config validada, logs JSON e health checks. A plataforma Java, o banco e o relay chegam na F1. Cadastro, login e chave no Secrets Manager chegam na F2.

## Responsabilidades

[RFC 0002](../../docs/rfc/0002-authentication-and-authorization.md):

- cadastro (com outbox `UserRegistered`), login, refresh com rotação e logout (`TokenRevoked`);
- chave de assinatura RS256 lida do Secrets Manager (`ticketpro/auth/jwt-signing-key`), publicada em `/.well-known/jwks.json`;
- compensação do cadastro ao consumir `UserCreationFailed`: remove a credencial e revoga os tokens.

## API (via gateway)

| Endpoint | Fase | Resposta |
|---|---|---|
| `GET /health/live` | F0 ✅ | `200 {"status":"up"}` |
| `GET /health/ready` | F0 ✅ (`db`/`broker` na F1) | `200 {"status":"up","checks":{}}` |
| `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout` | F2 | RFC 0002 §5.1 |
| `GET /.well-known/jwks.json` (interno) | F2 | chaves públicas |

## Eventos

| Publica (tópico `ticketpro-auth`) | Consome (fila `ticketpro-auth-service`) |
|---|---|
| `UserRegistered`, `TokenRevoked` | `UserCreationFailed` |

## Dados

O banco é `auth`, no container `auth-db`. Tabelas: `credentials` (`email UNIQUE`), `refresh_tokens` (`jti UNIQUE`), `revoked_tokens`, `outbox` e `processed_messages`. O Redis próprio guarda a blacklist de `jti`.

## Por onde começar

1. Ler a seção *Primeiros passos* do [README da raiz](../../README.md).
2. Ler:
   - [RFC 0002](../../docs/rfc/0002-authentication-and-authorization.md) inteira;
   - [arquitetura](../../docs/arquitetura.md) §5 (cadastro e compensação);
   - [padrões](../../docs/padroes.md) §2.2 (estrutura Java) e §6–7.
3. **F1:** Tasks 11–12 do [plano F1](../../docs/superpowers/plans/2026-11-12-f1-platform.md):
   - a camada `platform/` em Java, seguindo o *Platform contract* e os testes P01–P15;
   - banco, migrations, relay e readiness.
4. **F2:** register, login, JWKS e a chave do Secrets Manager ([roadmap](../../docs/roadmap.md)).

## Variáveis de ambiente

| Variável | Obrigatória | Exemplo |
|---|---|---|
| `SERVICE_NAME` | sim | `auth-service` |
| `PORT` | sim | `8080` |
| `LOG_LEVEL` | sim | `debug` \| `info` \| `warn` \| `error` |

## Comandos

```bash
make test        # JUnit 5
make lint        # Spotless (google-java-format)
make typecheck   # compila com warnings como erro
make run         # sobe só este serviço
```

Sem JDK instalado, os alvos rodam sozinhos no container `eclipse-temurin:21-jdk`. Para forçar: `make test TOOLCHAIN=docker`. Também há um Dev Container em [`.devcontainer/`](.devcontainer/devcontainer.json).
