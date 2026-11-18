//go:build integration

package messaging_test

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/codes"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"

	"github.com/ticketpro/platform/messaging"
	"github.com/ticketpro/platform/tracing"
)

func recordSpans(t *testing.T) (*tracetest.SpanRecorder, *sdktrace.TracerProvider) {
	t.Helper()
	rec := tracetest.NewSpanRecorder()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSpanProcessor(rec))
	otel.SetTracerProvider(tp)
	t.Cleanup(func() { _ = tp.Shutdown(context.Background()) })
	return rec, tp
}

func consumerSpan(t *testing.T, rec *tracetest.SpanRecorder) sdktrace.ReadOnlySpan {
	t.Helper()
	for _, s := range rec.Ended() {
		if s.SpanKind() == trace.SpanKindConsumer {
			return s
		}
	}
	t.Fatalf("no consumer span in %d spans", len(rec.Ended()))
	return nil
}

func (f *fixture) sendWithTraceparent(body, traceparent string) {
	f.t.Helper()
	in := &sqs.SendMessageInput{QueueUrl: aws.String(f.queueURL), MessageBody: aws.String(body)}
	if traceparent != "" {
		in.MessageAttributes = map[string]sqstypes.MessageAttributeValue{
			"traceparent": {DataType: aws.String("String"), StringValue: aws.String(traceparent)},
		}
	}
	if _, err := f.sqs.SendMessage(context.Background(), in); err != nil {
		f.t.Fatal(err)
	}
}

// ADR-0012: the consumer span continues the producer's trace and links to the producer span.
func TestConsumerSpanContinuesTheProducerTrace(t *testing.T) {
	rec, tp := recordSpans(t)
	f := setup(t, map[string]messaging.Handler{"TokenRevoked": &effectHandler{}})
	ctx, producer := tp.Tracer("test").Start(context.Background(), "publish", trace.WithSpanKind(trace.SpanKindProducer))
	f.sendWithTraceparent(example(t, "TokenRevoked.v1", msgID), tracing.Inject(ctx))
	producer.End()
	f.receiveAndProcess(f.queueURL)

	s := consumerSpan(t, rec)
	if s.SpanContext().TraceID() != producer.SpanContext().TraceID() || s.Parent().SpanID() != producer.SpanContext().SpanID() {
		t.Fatal("consumer span is not in the producer trace")
	}
	if len(s.Links()) != 1 || s.Links()[0].SpanContext.SpanID() != producer.SpanContext().SpanID() {
		t.Fatalf("links %v", s.Links())
	}
}

// Review Focus 1: a message without traceparent gets a root consumer span.
func TestConsumerSpanWithoutTraceparentIsARoot(t *testing.T) {
	rec, _ := recordSpans(t)
	f := setup(t, map[string]messaging.Handler{"TokenRevoked": &effectHandler{}})
	f.sendWithTraceparent(example(t, "TokenRevoked.v1", msgID), "")
	f.receiveAndProcess(f.queueURL)
	if s := consumerSpan(t, rec); s.Parent().IsValid() {
		t.Fatal("consumer span without traceparent must be a root span")
	}
}

// Review Focus 4: a failing handler leaves the consumer span in error.
func TestConsumerSpanRecordsHandlerFailure(t *testing.T) {
	rec, _ := recordSpans(t)
	f := setup(t, map[string]messaging.Handler{"TokenRevoked": &effectHandler{result: errors.New("boom")}})
	f.sendWithTraceparent(example(t, "TokenRevoked.v1", msgID), "")
	f.receiveAndProcess(f.queueURL)
	s := consumerSpan(t, rec)
	if s.Status().Code != codes.Error {
		t.Fatalf("status %v, want Error", s.Status())
	}
	// The cause travels with the span, not only in the log.
	found := false
	for _, e := range s.Events() {
		for _, a := range e.Attributes {
			if a.Key == "exception.message" && strings.Contains(a.Value.AsString(), "boom") {
				found = true
			}
		}
	}
	if !found {
		t.Fatalf("no exception event with the handler error: %v", s.Events())
	}
}
