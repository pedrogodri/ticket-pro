# Standard targets (docs/padroes.md §1.2). Runs from this folder only.
MVN := ./mvnw -q -B

.PHONY: build test test-integration lint fmt typecheck run

build:
	$(MVN) -DskipTests package

test:
	$(MVN) test

test-integration:
	$(MVN) verify

lint:
	$(MVN) spotless:check

fmt:
	$(MVN) spotless:apply

typecheck:
	$(MVN) compile -Dmaven.compiler.failOnWarning=true -Dmaven.compiler.showWarnings=true

run:
	docker compose -f compose.yaml up --build
