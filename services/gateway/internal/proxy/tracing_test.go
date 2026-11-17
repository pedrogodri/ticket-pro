package proxy

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"
)

// Review Focus 3: the upstream gets a traceparent from the gateway's own trace (ADR-0012).
func TestUpstreamReceivesTheGatewayTraceparent(t *testing.T) {
	rec := tracetest.NewSpanRecorder()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSpanProcessor(rec))
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.TraceContext{})
	t.Cleanup(func() { _ = tp.Shutdown(context.Background()) })

	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Get("traceparent")
	}))
	t.Cleanup(srv.Close)
	u, _ := url.Parse(srv.URL)
	h := New(Config{
		Routes: map[string]*url.URL{"events": u}, Public: map[string]bool{"GET /events": true},
		MaxBodyBytes: 1024, UpstreamTimeout: time.Second, Verifier: fakeVerifier{},
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})

	ctx, span := tp.Tracer("test").Start(context.Background(), "gateway request")
	req := httptest.NewRequest("GET", "/events", nil).WithContext(ctx)
	h.ServeHTTP(httptest.NewRecorder(), req)
	span.End()

	sc := trace.SpanContextFromContext(propagation.TraceContext{}.Extract(context.Background(), propagation.HeaderCarrier{"Traceparent": {got}}))
	if !sc.IsValid() || sc.TraceID() != span.SpanContext().TraceID() {
		t.Fatalf("upstream traceparent %q is not in the gateway trace %s", got, span.SpanContext().TraceID())
	}
	if sc.SpanID() == span.SpanContext().SpanID() {
		t.Fatal("upstream should see the client span of the proxy call, not the server span itself")
	}
}
