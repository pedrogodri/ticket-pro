# Build context: this folder only (ADR-0016).
FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/inventory-service ./cmd/inventory-service

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/inventory-service /inventory-service
COPY --from=build /src/contracts /contracts
USER nonroot:nonroot
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=10 CMD ["/inventory-service", "healthcheck"]
ENTRYPOINT ["/inventory-service"]
