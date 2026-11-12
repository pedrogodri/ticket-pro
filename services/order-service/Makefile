# Standard targets (docs/padroes.md §1.2). Runs from this folder only.
.PHONY: deps build test test-integration lint fmt typecheck run

node_modules: package.json package-lock.json
	npm ci --no-audit --no-fund
	@touch node_modules

deps: node_modules

build: node_modules
	npm run build

test: node_modules
	npm test

test-integration: node_modules
	npx vitest run --dir tests/integration --passWithNoTests

lint: node_modules
	npm run lint

fmt: node_modules
	npm run fmt

typecheck: node_modules
	npm run typecheck

run:
	docker compose -f compose.yaml up --build
