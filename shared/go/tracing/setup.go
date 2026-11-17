package tracing

import (
	"context"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
)

// Config selects where traces go (ADR-0012). Endpoint is OTEL_EXPORTER_OTLP_ENDPOINT (host:port, gRPC);
// empty disables export but still installs the W3C propagator.
type Config struct {
	ServiceName string
	Endpoint    string
}

// Setup installs the global tracer provider and propagator, and returns the function that flushes
// pending spans on shutdown. The exporter connects lazily: an unreachable collector never blocks startup.
func Setup(ctx context.Context, cfg Config) (func(context.Context) error, error) {
	otel.SetTextMapPropagator(propagator)
	if cfg.Endpoint == "" {
		return func(context.Context) error { return nil }, nil
	}
	exporter, err := otlptracegrpc.New(ctx, otlptracegrpc.WithEndpoint(cfg.Endpoint), otlptracegrpc.WithInsecure())
	if err != nil {
		return nil, err
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(resource.NewSchemaless(semconv.ServiceName(cfg.ServiceName))),
	)
	otel.SetTracerProvider(tp)
	return tp.Shutdown, nil
}
