# Standard targets (docs/padroes.md §1.2). Runs from this folder only.
#
# TOOLCHAIN=local  uses the .NET SDK installed on your machine (.NET 10).
# TOOLCHAIN=docker runs every command inside $(SDK_IMAGE) (nothing to install but Docker).
# Default: local when `dotnet` is on PATH, docker otherwise. Override: `make test TOOLCHAIN=docker`.
# Do not mix modes in the same checkout: bin/ and obj/ store absolute paths; `git clean -fdX` resets them.
SDK_IMAGE ?= mcr.microsoft.com/dotnet/sdk:10.0
SLN := PaymentService.sln
TOOLCHAIN ?= $(if $(shell command -v dotnet 2>/dev/null),local,docker)

ifeq ($(TOOLCHAIN),docker)
# Docker socket + host override let Testcontainers start sibling containers (integration tests).
RUN := docker run --rm -v "$(CURDIR):/src" -w /src \
	-v ticketpro-nuget-cache:/root/.nuget/packages \
	-e DOTNET_CLI_TELEMETRY_OPTOUT=1 -e DOTNET_NOLOGO=1 \
	-v /var/run/docker.sock:/var/run/docker.sock \
	-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --add-host=host.docker.internal:host-gateway \
	$(SDK_IMAGE)
else
RUN :=
endif

.PHONY: build test test-integration lint fmt typecheck run

build:
	$(RUN) dotnet build $(SLN) -c Release

test:
	$(RUN) dotnet test $(SLN) --filter "Category!=Integration"

test-integration:
	$(RUN) dotnet test $(SLN) --filter "Category=Integration"

lint:
	$(RUN) dotnet format $(SLN) --verify-no-changes

fmt:
	$(RUN) dotnet format $(SLN)

typecheck:
	$(RUN) dotnet build $(SLN) -warnaserror

run:
	docker compose -f compose.yaml up --build
