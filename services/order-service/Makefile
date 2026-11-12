# Standard targets (docs/padroes.md §1.2). Runs from this folder only.
#
# TOOLCHAIN=local  uses the Node installed on your machine (Node 22).
# TOOLCHAIN=docker runs every command inside $(NODE_IMAGE) (nothing to install but Docker).
# Default: local when `node` is on PATH, docker otherwise. Override: `make test TOOLCHAIN=docker`.
# In docker mode node_modules lives in a named volume, so Linux binaries never land in your checkout.
NODE_IMAGE ?= node:22
TOOLCHAIN ?= $(if $(shell command -v node 2>/dev/null),local,docker)

ifeq ($(TOOLCHAIN),docker)
# Docker socket + host override let Testcontainers start sibling containers (integration tests).
RUN := docker run --rm -v "$(CURDIR):/src" -w /src \
	-v ticketpro-order-service-node-modules:/src/node_modules \
	-v ticketpro-npm-cache:/root/.npm \
	-v /var/run/docker.sock:/var/run/docker.sock \
	-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --add-host=host.docker.internal:host-gateway \
	$(NODE_IMAGE)
else
RUN :=
endif

# Reinstalls only when package-lock.json differs from the copy saved at the last install.
DEPS := cmp -s package-lock.json node_modules/.installed-lock || \
	{ npm ci --no-audit --no-fund && cp package-lock.json node_modules/.installed-lock; }

.PHONY: deps build test test-integration lint fmt typecheck run

deps:
	$(RUN) sh -c '$(DEPS)'

build:
	$(RUN) sh -c '$(DEPS) && npm run build'

test:
	$(RUN) sh -c '$(DEPS) && npm test'

test-integration:
	$(RUN) sh -c '$(DEPS) && npx vitest run --dir tests/integration --passWithNoTests'

lint:
	$(RUN) sh -c '$(DEPS) && npm run lint'

fmt:
	$(RUN) sh -c '$(DEPS) && npm run fmt'

typecheck:
	$(RUN) sh -c '$(DEPS) && npm run typecheck'

run:
	docker compose -f compose.yaml up --build
