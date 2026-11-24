# Build context: this folder only (ADR-0016).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app -g 10001 && adduser -S app -u 10001 -G app
WORKDIR /app
# OpenTelemetry Java agent (ADR-0012, RFC 0004 §5.9), version and sha256 pinned (the checksum Maven
# Central publishes). Loaded only when OTEL_EXPORTER_OTLP_ENDPOINT is set, so nothing changes without it.
ADD --checksum=sha256:da44154cea128257779c8a782b9651fd7c0c6aa177cd3810f9a867ac8509555d --chmod=444 \
  https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.34.0/opentelemetry-javaagent-2.34.0.jar \
  /app/opentelemetry-javaagent.jar
COPY --from=build /src/target/order-service.jar /app/app.jar
COPY contracts /app/contracts
USER 10001:10001
EXPOSE 8080
# The unsampled traceparent (flags 00) keeps health checks out of Jaeger (ADR-0012).
HEALTHCHECK --interval=5s --timeout=3s --retries=20 --start-period=20s \
  CMD wget -qO- --header "traceparent: 00-00000000000000000000000000000001-0000000000000001-00" "http://127.0.0.1:${PORT:-8080}/health/live" >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java ${OTEL_EXPORTER_OTLP_ENDPOINT:+-javaagent:/app/opentelemetry-javaagent.jar} -XX:MaxRAMPercentage=75 -jar /app/app.jar"]
