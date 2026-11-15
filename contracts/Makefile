# Contract validation (RFC 0001 §6). Same TOOLCHAIN switch as the services:
# local when `node` is on PATH, docker otherwise. Override: `make validate TOOLCHAIN=docker`.
NODE_IMAGE ?= node:22
TOOLCHAIN ?= $(if $(shell command -v node 2>/dev/null),local,docker)

ifeq ($(TOOLCHAIN),docker)
RUN := docker run --rm -v "$(CURDIR):/src" -w /src \
	-v ticketpro-contracts-node-modules:/src/node_modules \
	-v ticketpro-npm-cache:/root/.npm \
	$(NODE_IMAGE)
else
RUN :=
endif

DEPS := cmp -s package-lock.json node_modules/.installed-lock || \
	{ npm ci --no-audit --no-fund && cp package-lock.json node_modules/.installed-lock; }

.PHONY: validate typecheck check

validate:
	$(RUN) sh -c '$(DEPS) && npm run validate'

typecheck:
	$(RUN) sh -c '$(DEPS) && npm run typecheck'

check: validate typecheck
