# Build context: this folder only (ADR-0016).
FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/autoscaler ./cmd/autoscaler

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/autoscaler /autoscaler
USER nonroot:nonroot
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=10 CMD ["/autoscaler", "healthcheck"]
ENTRYPOINT ["/autoscaler"]
