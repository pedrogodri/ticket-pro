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
COPY --from=build /src/target/order-service.jar /app/app.jar
COPY contracts /app/contracts
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=20 --start-period=20s \
  CMD wget -qO- "http://127.0.0.1:${PORT:-8080}/health/live" >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
