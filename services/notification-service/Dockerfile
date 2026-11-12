# Build context: this folder only (ADR-0016).
FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/notification-service ./cmd/notification-service

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/notification-service /notification-service
COPY --from=build /src/contracts /contracts
USER nonroot:nonroot
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=10 CMD ["/notification-service", "healthcheck"]
ENTRYPOINT ["/notification-service"]
