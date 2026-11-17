//go:build integration

package outbox_test

import (
	"context"
	"testing"
	"time"

	"go.opentelemetry.io/otel"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"

	"github.com/ticketpro/platform/envelope"
	"github.com/ticketpro/platform/internal/testenv"
	"github.com/ticketpro/platform/outbox"
	"github.com/ticketpro/platform/tracing"
)

type capturingPublisher struct{ traceparent string }

func (p *capturingPublisher) Publish(_ context.Context, _ string, _ envelope.Envelope, tp string) error {
	p.traceparent = tp
	return nil
}

// ADR-0012: the relay publishes inside a producer span that continues the trace stored with the row.
func TestRelayPublishesInsideAProducerSpan(t *testing.T) {
	rec := tracetest.NewSpanRecorder()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSpanProcessor(rec))
	otel.SetTracerProvider(tp)
	t.Cleanup(func() { _ = tp.Shutdown(context.Background()) })

	pool := testenv.Postgres(t)
	ctx, root := tp.Tracer("test").Start(context.Background(), "request")
	env := newEnvelope(t)
	enqueue(t, ctx, pool, topicARN, env, true)
	root.End()

	pub := &capturingPublisher{}
	relay := outbox.NewRelay(outbox.RelayConfig{Pool: pool, Publisher: pub, BatchSize: 50, ClaimLease: 30 * time.Second, Logger: quiet})
	if _, err := relay.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	var producer sdktrace.ReadOnlySpan
	for _, s := range rec.Ended() {
		if s.SpanKind() == trace.SpanKindProducer {
			producer = s
		}
	}
	if producer == nil {
		t.Fatalf("no producer span in %d spans", len(rec.Ended()))
	}
	if producer.Parent().SpanID() != root.SpanContext().SpanID() || producer.SpanContext().TraceID() != root.SpanContext().TraceID() {
		t.Fatal("producer span is not a child of the enqueuing span")
	}
	published := trace.SpanContextFromContext(tracing.Extract(context.Background(), pub.traceparent))
	if published.SpanID() != producer.SpanContext().SpanID() {
		t.Fatalf("published traceparent %q is not the producer span", pub.traceparent)
	}
}
