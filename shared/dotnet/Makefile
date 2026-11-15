# .NET platform library (ADR-0017). Same TOOLCHAIN switch as the C# services:
# local when `dotnet` is on PATH, docker otherwise. Override: `make test TOOLCHAIN=docker`.
# The docker mode mounts the repository root because tests read ../../contracts (P04).
SDK_IMAGE ?= mcr.microsoft.com/dotnet/sdk:10.0
SLN := TicketPro.Platform.slnx
TOOLCHAIN ?= $(if $(shell command -v dotnet 2>/dev/null),local,docker)
REPO_ROOT := $(abspath $(CURDIR)/../..)

ifeq ($(TOOLCHAIN),docker)
# Docker socket + host override let Testcontainers start sibling containers (integration tests).
RUN := docker run --rm -v "$(REPO_ROOT):/repo" -w /repo/shared/dotnet \
	-v ticketpro-nuget-cache:/root/.nuget/packages \
	-e DOTNET_CLI_TELEMETRY_OPTOUT=1 -e DOTNET_NOLOGO=1 \
	-v /var/run/docker.sock:/var/run/docker.sock \
	-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --add-host=host.docker.internal:host-gateway \
	$(SDK_IMAGE)
else
RUN :=
endif

.PHONY: build test test-integration lint fmt typecheck

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
