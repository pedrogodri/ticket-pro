# Standard targets (docs/padroes.md §1.2). Runs from this folder only.
#
# TOOLCHAIN=local  uses the Go installed on your machine.
# TOOLCHAIN=docker runs every command inside $(GO_IMAGE) (nothing to install but Docker).
# Default: local when `go` is on PATH, docker otherwise. Override: `make test TOOLCHAIN=docker`.
GO_IMAGE ?= golang:1.25
GOLANGCI_IMAGE ?= golangci/golangci-lint:v2.15.0-alpine
TOOLCHAIN ?= $(if $(shell command -v go 2>/dev/null),local,docker)

ifeq ($(TOOLCHAIN),docker)
# Docker socket + host override let Testcontainers start sibling containers (integration tests).
RUN := docker run --rm -v "$(CURDIR):/src" -w /src \
	-v ticketpro-go-cache:/go/pkg -e GOCACHE=/go/pkg/.build-cache \
	-v /var/run/docker.sock:/var/run/docker.sock \
	-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --add-host=host.docker.internal:host-gateway \
	$(GO_IMAGE)
else
RUN :=
endif

.PHONY: build test test-integration lint fmt typecheck run

build:
	$(RUN) go build ./...

test:
	$(RUN) go test ./...

test-integration:
	$(RUN) go test -tags integration ./...

lint:
	$(RUN) sh -c 'u="$$(gofmt -l .)"; if [ -n "$$u" ]; then echo "gofmt needed: $$u"; exit 1; fi'
	docker run --rm -v "$(CURDIR):/app" -w /app $(GOLANGCI_IMAGE) golangci-lint run ./...

fmt:
	$(RUN) gofmt -w .

typecheck:
	$(RUN) go vet ./...

run:
	docker compose -f compose.yaml up --build
