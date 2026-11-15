package tracing

import (
	"context"
	"regexp"
	"testing"

	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

func TestP05_TraceparentRoundTrip(t *testing.T) {
	tp := sdktrace.NewTracerProvider()
	t.Cleanup(func() { _ = tp.Shutdown(context.Background()) })

	ctx, span := tp.Tracer("test").Start(context.Background(), "op")
	defer span.End()

	header := Inject(ctx)
	if !regexp.MustCompile(`^00-[0-9a-f]{32}-[0-9a-f]{16}-01$`).MatchString(header) {
		t.Fatalf("unexpected traceparent %q", header)
	}
	got := trace.SpanContextFromContext(Extract(context.Background(), header))
	if got.TraceID() != span.SpanContext().TraceID() {
		t.Fatalf("trace id not preserved: %s vs %s", got.TraceID(), span.SpanContext().TraceID())
	}
	if Inject(context.Background()) != "" {
		t.Fatal("inject without span must be empty")
	}
}
