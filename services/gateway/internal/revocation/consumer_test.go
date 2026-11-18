package revocation

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/redis/go-redis/v9"
	"go.opentelemetry.io/otel"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"

	"github.com/ticketpro/gateway/internal/platform/envelope"
	"github.com/ticketpro/gateway/internal/platform/tracing"
)

type fakeQueue struct {
	mu          sync.Mutex
	receiveOpts sqs.Options
	deleted     int
	visibility  []int32
}

func (q *fakeQueue) ReceiveMessage(ctx context.Context, _ *sqs.ReceiveMessageInput, optFns ...func(*sqs.Options)) (*sqs.ReceiveMessageOutput, error) {
	q.mu.Lock()
	for _, fn := range optFns {
		fn(&q.receiveOpts)
	}
	q.mu.Unlock()
	<-ctx.Done()
	return nil, ctx.Err()
}

func (q *fakeQueue) DeleteMessage(context.Context, *sqs.DeleteMessageInput, ...func(*sqs.Options)) (*sqs.DeleteMessageOutput, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.deleted++
	return &sqs.DeleteMessageOutput{}, nil
}

func (q *fakeQueue) ChangeMessageVisibility(_ context.Context, in *sqs.ChangeMessageVisibilityInput, _ ...func(*sqs.Options)) (*sqs.ChangeMessageVisibilityOutput, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.visibility = append(q.visibility, in.VisibilityTimeout)
	return &sqs.ChangeMessageVisibilityOutput{}, nil
}

var now = time.Date(2026, 11, 20, 18, 30, 0, 0, time.UTC)

func newConsumer(t *testing.T, logs io.Writer) (*Consumer, *fakeQueue, *miniredis.Miniredis) {
	t.Helper()
	validator, err := envelope.NewValidator("../../contracts")
	if err != nil {
		t.Fatal(err)
	}
	mr := miniredis.RunT(t)
	q := &fakeQueue{}
	c := NewConsumer(ConsumerConfig{
		Queue:     q,
		QueueURL:  "http://ministack:4566/000000000000/ticketpro-gateway",
		Store:     Store{Client: redis.NewClient(&redis.Options{Addr: mr.Addr()})},
		Validator: validator,
		Clock:     func() time.Time { return now },
		Logger:    slog.New(slog.NewTextHandler(logs, nil)),
	})
	return c, q, mr
}

// snsMessage wraps an envelope the way SNS delivers it to SQS.
func snsMessage(t *testing.T, payload map[string]any) sqstypes.Message {
	t.Helper()
	env := map[string]any{
		"messageId": "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55", "correlationId": "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c",
		"type": "TokenRevoked", "version": 1, "occurredAt": "2026-11-20T18:30:00.000Z", "payload": payload,
	}
	raw, _ := json.Marshal(env)
	body, _ := json.Marshal(map[string]string{"Type": "Notification", "Message": string(raw)})
	return sqstypes.Message{Body: aws.String(string(body)), MessageId: aws.String("m-1"), ReceiptHandle: aws.String("r-1"),
		Attributes: map[string]string{"ApproximateReceiveCount": "1"}}
}

func validPayload(expiresAt time.Time) map[string]any {
	return map[string]any{"jti": "0193a6f0-8888-7000-8000-000000000008", "userId": "0193a6f0-1111-7000-8000-000000000001",
		"expiresAt": expiresAt.Format("2006-01-02T15:04:05.000Z"), "reason": "LOGOUT"}
}

func TestTokenRevokedStoresJtiAndAcks(t *testing.T) {
	c, q, mr := newConsumer(t, io.Discard)
	if got := c.Process(context.Background(), snsMessage(t, validPayload(now.Add(10*time.Minute)))); got != Succeeded {
		t.Fatalf("outcome %v", got)
	}
	if !mr.Exists("revoked:0193a6f0-8888-7000-8000-000000000008") || mr.TTL("revoked:0193a6f0-8888-7000-8000-000000000008") != 10*time.Minute {
		t.Fatalf("keys %v", mr.Keys())
	}
	if q.deleted != 1 {
		t.Fatalf("deleted %d", q.deleted)
	}
}

func TestDuplicateDeliveriesHaveOneEffect(t *testing.T) {
	c, q, mr := newConsumer(t, io.Discard)
	msg := snsMessage(t, validPayload(now.Add(time.Minute)))
	for range 5 {
		c.Process(context.Background(), msg)
	}
	if len(mr.Keys()) != 1 || q.deleted != 5 {
		t.Fatalf("keys %v deleted %d", mr.Keys(), q.deleted)
	}
}

func TestExpiredTokenIsAckedWithoutKey(t *testing.T) {
	c, q, mr := newConsumer(t, io.Discard)
	if got := c.Process(context.Background(), snsMessage(t, validPayload(now.Add(-time.Minute)))); got != Succeeded {
		t.Fatalf("outcome %v", got)
	}
	if len(mr.Keys()) != 0 || q.deleted != 1 {
		t.Fatalf("keys %v deleted %d", mr.Keys(), q.deleted)
	}
}

func TestInvalidMessageIsPoison(t *testing.T) {
	var logs strings.Builder
	c, q, _ := newConsumer(t, &logs)
	p := validPayload(now)
	delete(p, "jti")
	if got := c.Process(context.Background(), snsMessage(t, p)); got != Poison {
		t.Fatalf("outcome %v", got)
	}
	if q.deleted != 0 || len(q.visibility) != 1 || q.visibility[0] != 0 || !strings.Contains(logs.String(), "message_rejected") {
		t.Fatalf("deleted %d visibility %v logs %s", q.deleted, q.visibility, logs.String())
	}
}

func TestRedisDownIsRetried(t *testing.T) {
	var logs strings.Builder
	c, q, mr := newConsumer(t, &logs)
	mr.Close()
	if got := c.Process(context.Background(), snsMessage(t, validPayload(now.Add(time.Minute)))); got != Transient {
		t.Fatalf("outcome %v", got)
	}
	if q.deleted != 0 || len(q.visibility) != 1 || q.visibility[0] < 1 || !strings.Contains(logs.String(), "message_retry") {
		t.Fatalf("deleted %d visibility %v logs %s", q.deleted, q.visibility, logs.String())
	}
}

// The long poll waits WaitTimeS: the receive must not use the short AWS client timeout (RFC 0003 §5.1).
func TestReceiveExtendsTheHTTPTimeoutForTheLongPoll(t *testing.T) {
	c, q, _ := newConsumer(t, io.Discard)
	c.cfg.WaitTimeS, c.cfg.RequestTimeout = 10, 3*time.Second
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	_ = c.Run(ctx)
	client, ok := q.receiveOpts.HTTPClient.(*http.Client)
	if !ok || client.Timeout != 13*time.Second {
		t.Fatalf("receive HTTP client %#v", q.receiveOpts.HTTPClient)
	}
}

// ADR-0012: the TokenRevoked consumer span continues the producer's trace (raw delivery keeps the
// traceparent in the SQS message attributes) and links to it.
func TestConsumerSpanContinuesTheProducerTrace(t *testing.T) {
	rec := tracetest.NewSpanRecorder()
	tp := sdktrace.NewTracerProvider(sdktrace.WithSpanProcessor(rec))
	otel.SetTracerProvider(tp)
	t.Cleanup(func() { _ = tp.Shutdown(context.Background()) })

	c, _, _ := newConsumer(t, io.Discard)
	ctx, producer := tp.Tracer("test").Start(context.Background(), "publish", trace.WithSpanKind(trace.SpanKindProducer))
	msg := snsMessage(t, validPayload(now.Add(time.Minute)))
	msg.MessageAttributes = map[string]sqstypes.MessageAttributeValue{
		"traceparent": {DataType: aws.String("String"), StringValue: aws.String(tracing.Inject(ctx))},
	}
	producer.End()
	c.Process(context.Background(), msg)

	var span sdktrace.ReadOnlySpan
	for _, s := range rec.Ended() {
		if s.SpanKind() == trace.SpanKindConsumer {
			span = s
		}
	}
	if span == nil || span.SpanContext().TraceID() != producer.SpanContext().TraceID() || len(span.Links()) != 1 {
		t.Fatalf("consumer span %+v", span)
	}
}
