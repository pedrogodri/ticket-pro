# Standard targets (docs/padroes.md §1.2). Runs from this folder only.
#
# TOOLCHAIN=local  uses the JDK installed on your machine (Java 21).
# TOOLCHAIN=docker runs every command inside $(JDK_IMAGE) (nothing to install but Docker).
# Default: local when `java` is on PATH, docker otherwise. Override: `make test TOOLCHAIN=docker`.
JDK_IMAGE ?= eclipse-temurin:21-jdk
TOOLCHAIN ?= $(if $(shell command -v java 2>/dev/null),local,docker)

ifeq ($(TOOLCHAIN),docker)
# Docker socket + host override let Testcontainers start sibling containers (integration tests).
RUN := docker run --rm -v "$(CURDIR):/src" -w /src \
	-v ticketpro-m2-cache:/root/.m2 \
	-v /var/run/docker.sock:/var/run/docker.sock \
	-e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --add-host=host.docker.internal:host-gateway \
	$(JDK_IMAGE)
else
RUN :=
endif

MVN := $(RUN) ./mvnw -q -B

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
