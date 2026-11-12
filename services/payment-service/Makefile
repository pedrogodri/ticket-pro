# Standard targets (docs/padroes.md §1.2). Runs from this folder only.
.PHONY: build test test-integration lint fmt typecheck run

build:
	dotnet build PaymentService.sln -c Release

test:
	dotnet test PaymentService.sln

test-integration:
	dotnet test PaymentService.sln --filter "Category=Integration"

lint:
	dotnet format PaymentService.sln --verify-no-changes

fmt:
	dotnet format PaymentService.sln

typecheck:
	dotnet build PaymentService.sln -warnaserror

run:
	docker compose -f compose.yaml up --build
