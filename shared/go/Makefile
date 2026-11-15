# Go platform library (ADR-0017). Same TOOLCHAIN switch as the Go services:
# local when `go` is on PATH, docker otherwise. Override: `make test TOOLCHAIN=docker`.
# The docker mode mounts the repository root because tests read ../../contracts (P04).
GO_IMAGE ?= golang:1.25
GOLANGCI_IMAGE ?= golangci/golangci-lint:v2.15.0-alpine
TOOLCHAIN ?= $(if $(shell command -v go 2>/dev/null),local,docker)
REPO_ROOT := $(abspath $(CURDIR)/../..)

ifeq ($(TOOLCHAIN),docker)
# Docker socket + host override let Testcontainers start sibling containers (integration tests).
RUN := docker run --rm -v "$(REPO_ROOT):/repo" -w /repo/shared/go \
	-v ticketpro-go-cache:/go/pkg -e GOCACHE=/go/pkg/.build-cache \
	-v /var/run/docker.sock:/var/run/docker.sock \
	-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --add-host=host.docker.internal:host-gateway \
	$(GO_IMAGE)
else
RUN :=
endif

.PHONY: build test test-integration lint fmt typecheck

build:
	$(RUN) go build ./...

test:
	$(RUN) go test ./...

test-integration:
	$(RUN) go test -tags integration -count=1 ./...

lint:
	$(RUN) sh -c 'u="$$(gofmt -l .)"; if [ -n "$$u" ]; then echo "gofmt needed: $$u"; exit 1; fi'
	docker run --rm -v "$(CURDIR):/app" -w /app $(GOLANGCI_IMAGE) golangci-lint run --build-tags integration ./...

fmt:
	$(RUN) gofmt -w .

typecheck:
	$(RUN) go vet ./...
