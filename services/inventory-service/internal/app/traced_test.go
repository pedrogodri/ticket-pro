package app

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"go.opentelemetry.io/otel"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
)

// Server spans are named after the route, ids collapsed, so Jaeger tells requests apart.
func TestServerSpansAreNamedAfterTheRoute(t *testing.T) {
	rec := tracetest.NewSpanRecorder()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSpanProcessor(rec))
	otel.SetTracerProvider(tp)
	t.Cleanup(func() { _ = tp.Shutdown(context.Background()) })

	h := traced(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}), "svc")
	for _, path := range []string{"/events/0193a6f0-3333-7000-8000-000000000003", "/orders/42", "/health/ready"} {
		h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", path, nil))
	}
	var names []string
	for _, s := range rec.Ended() {
		names = append(names, s.Name())
	}
	if len(names) != 2 || names[0] != "GET /events/{id}" || names[1] != "GET /orders/{id}" {
		t.Fatalf("span names %v", names)
	}
}
