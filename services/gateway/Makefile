# Standard targets (docs/padroes.md §1.2). Runs from this folder only.
GOLANGCI_IMAGE ?= golangci/golangci-lint:v2.15.0-alpine

.PHONY: build test test-integration lint fmt typecheck run

build:
	go build ./...

test:
	go test ./...

test-integration:
	go test -tags integration ./...

lint:
	@unformatted="$$(gofmt -l .)"; if [ -n "$$unformatted" ]; then echo "gofmt needed: $$unformatted"; exit 1; fi
	docker run --rm -v "$(CURDIR):/app" -w /app $(GOLANGCI_IMAGE) golangci-lint run ./...

fmt:
	gofmt -w .

typecheck:
	go vet ./...

run:
	docker compose -f compose.yaml up --build
