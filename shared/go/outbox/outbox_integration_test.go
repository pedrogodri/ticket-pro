//go:build integration

package outbox_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sns"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/jackc/pgx/v5/pgxpool"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"

	"github.com/ticketpro/platform/envelope"
	"github.com/ticketpro/platform/internal/testenv"
	"github.com/ticketpro/platform/messaging"
	"github.com/ticketpro/platform/outbox"
)

const topicARN = "arn:aws:sns:us-east-1:000000000000:ticketpro-test"

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

func newEnvelope(t *testing.T) envelope.Envelope {
	t.Helper()
	env, err := envelope.New("c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c", "Ping", 1, map[string]any{"n": 1})
	if err != nil {
		t.Fatal(err)
	}
	return env
}

func enqueue(t *testing.T, ctx context.Context, pool *pgxpool.Pool, topic string, env envelope.Envelope, commit bool) {
	t.Helper()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err := outbox.Enqueue(ctx, tx, topic, env); err != nil {
		t.Fatal(err)
	}
	if commit {
		err = tx.Commit(ctx)
	} else {
		err = tx.Rollback(ctx)
	}
	if err != nil {
		t.Fatal(err)
	}
}

func TestP08_EnqueueIsPartOfTheCallerTransaction(t *testing.T) {
	pool := testenv.Postgres(t)
	ctx := context.Background()

	committed := newEnvelope(t)
	enqueue(t, ctx, pool, topicARN, committed, true)
	enqueue(t, ctx, pool, topicARN, newEnvelope(t), false)

	if n := testenv.Count(t, pool, "SELECT count(*) FROM outbox"); n != 1 {
		t.Fatalf("want 1 row (rollback discarded), got %d", n)
	}
	var typ, corr, payload string
	var version int
	err := pool.QueryRow(ctx, `SELECT type, version, correlation_id::text, payload::text FROM outbox WHERE message_id = $1`,
		committed.MessageID).Scan(&typ, &version, &corr, &payload)
	if err != nil {
		t.Fatal(err)
	}
	if typ != "Ping" || version != 1 || corr != committed.CorrelationID || payload != `{"n": 1}` {
		t.Fatalf("row does not match envelope: %s v%d %s %s", typ, version, corr, payload)
	}
	if n := testenv.Count(t, pool, "SELECT count(*) FROM outbox WHERE traceparent IS NULL"); n != 1 {
		t.Fatal("traceparent must be NULL without an active span")
	}

	tp := sdktrace.NewTracerProvider()
	defer func() { _ = tp.Shutdown(ctx) }()
	spanCtx, span := tp.Tracer("test").Start(ctx, "op")
	traced := newEnvelope(t)
	enqueue(t, spanCtx, pool, topicARN, traced, true)
	span.End()
	if n := testenv.Count(t, pool, "SELECT count(*) FROM outbox WHERE message_id = $1 AND traceparent LIKE '00-%'", traced.MessageID); n != 1 {
		t.Fatal("traceparent must be stored when a span is active")
	}
}

func TestP12_RelayAccumulatesWhileBrokerIsDownAndDrainsAfter(t *testing.T) {
	pool := testenv.Postgres(t)
	cfg, containerID := testenv.MiniStack(t)
	ctx := context.Background()
	sqsClient, snsClient := sqs.NewFromConfig(cfg), sns.NewFromConfig(cfg)
	queueURL, _ := testenv.Queue(t, sqsClient, "relay", 3)
	topic := testenv.Topic(t, snsClient, sqsClient, "relay", queueURL)

	tp := sdktrace.NewTracerProvider()
	defer func() { _ = tp.Shutdown(ctx) }()
	spanCtx, span := tp.Tracer("test").Start(ctx, "op")
	envs := []envelope.Envelope{newEnvelope(t), newEnvelope(t), newEnvelope(t)}
	enqueue(t, spanCtx, pool, topic, envs[0], true)
	span.End()
	enqueue(t, ctx, pool, topic, envs[1], true)
	enqueue(t, ctx, pool, topic, envs[2], true)

	relay := outbox.NewRelay(outbox.RelayConfig{
		Pool: pool, Publisher: messaging.NewSNSPublisher(snsClient), BatchSize: 10,
		PollInterval: 50 * time.Millisecond, MaxBackoff: time.Second, ClaimLease: 30 * time.Second, Logger: quiet,
	})

	testenv.Pause(t, containerID)
	paused := true
	t.Cleanup(func() {
		if paused {
			testenv.Unpause(t, containerID)
		}
	})
	published, err := relay.RunOnce(ctx)
	if published != 0 || err == nil {
		t.Fatalf("broker down: want 0 published and an error, got %d, %v", published, err)
	}
	if relay.BrokerUp() {
		t.Fatal("BrokerUp must be false after a failed publish")
	}
	if n := testenv.Count(t, pool, "SELECT count(*) FROM outbox WHERE published_at IS NULL"); n != 3 {
		t.Fatalf("all 3 rows must stay pending, got %d", n)
	}
	if n := testenv.Count(t, pool, "SELECT coalesce(sum(attempts),0) FROM outbox"); n < 1 {
		t.Fatal("the failed publish must be counted in attempts")
	}

	testenv.Unpause(t, containerID)
	paused = false
	runCtx, cancel := context.WithCancel(ctx)
	done := make(chan error, 1)
	go func() { done <- relay.Run(runCtx) }()
	deadline := time.Now().Add(30 * time.Second)
	for testenv.Count(t, pool, "SELECT count(*) FROM outbox WHERE published_at IS NULL") > 0 {
		if time.Now().After(deadline) {
			cancel()
			t.Fatal("relay did not drain the outbox within 30s after the broker came back")
		}
		time.Sleep(100 * time.Millisecond)
	}
	cancel()
	if err := <-done; err != nil && !errors.Is(err, context.Canceled) {
		t.Fatalf("Run returned %v", err)
	}
	if !relay.BrokerUp() {
		t.Fatal("BrokerUp must be true after draining")
	}

	got := map[string]sqstypes.Message{}
	for len(got) < 3 && time.Now().Before(deadline.Add(10*time.Second)) {
		out, err := sqsClient.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
			QueueUrl: aws.String(queueURL), MaxNumberOfMessages: 10, WaitTimeSeconds: 2,
			MessageAttributeNames: []string{"All"},
		})
		if err != nil {
			t.Fatal(err)
		}
		for _, m := range out.Messages {
			env, err := envelope.Unwrap([]byte(*m.Body))
			if err != nil {
				t.Fatal(err)
			}
			got[env.MessageID] = m
		}
	}
	for i, env := range envs {
		m, ok := got[env.MessageID]
		if !ok {
			t.Fatalf("message %d (%s) not delivered", i, env.MessageID)
		}
		attr := func(k string) string {
			if v, ok := m.MessageAttributes[k]; ok && v.StringValue != nil {
				return *v.StringValue
			}
			return ""
		}
		if attr("type") != "Ping" || attr("version") != "1" || attr("correlationId") != env.CorrelationID {
			t.Fatalf("message %d attributes: %+v", i, m.MessageAttributes)
		}
		if hasTrace := attr("traceparent") != ""; hasTrace != (i == 0) {
			t.Fatalf("message %d: traceparent present=%v, want %v", i, hasTrace, i == 0)
		}
	}
}

type countingPublisher struct {
	mu    sync.Mutex
	calls map[string]int
}

func (p *countingPublisher) Publish(_ context.Context, _ string, env envelope.Envelope, _ string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.calls[env.MessageID]++
	return nil
}

func TestP13_ConcurrentRelaysPublishEachRowOnce(t *testing.T) {
	pool := testenv.Postgres(t)
	ctx := context.Background()
	for i := 0; i < 20; i++ {
		enqueue(t, ctx, pool, topicARN, newEnvelope(t), true)
	}
	pub := &countingPublisher{calls: map[string]int{}}
	newRelay := func() *outbox.Relay {
		return outbox.NewRelay(outbox.RelayConfig{
			Pool: pool, Publisher: pub, BatchSize: 3, PollInterval: time.Millisecond,
			MaxBackoff: time.Second, ClaimLease: 30 * time.Second, Logger: quiet,
		})
	}
	var wg sync.WaitGroup
	for _, r := range []*outbox.Relay{newRelay(), newRelay()} {
		wg.Add(1)
		go func(r *outbox.Relay) {
			defer wg.Done()
			for testenv.Count(t, pool, "SELECT count(*) FROM outbox WHERE published_at IS NULL") > 0 {
				if _, err := r.RunOnce(ctx); err != nil {
					t.Error(err)
					return
				}
			}
		}(r)
	}
	wg.Wait()
	if len(pub.calls) != 20 {
		t.Fatalf("want 20 distinct messages published, got %d", len(pub.calls))
	}
	for id, n := range pub.calls {
		if n != 1 {
			t.Fatalf("message %s published %d times", id, n)
		}
	}
}

type slowPublisher struct{ delay time.Duration }

func (p slowPublisher) Publish(context.Context, string, envelope.Envelope, string) error {
	time.Sleep(p.delay)
	return nil
}

// Review finding: a slow (not failing) broker must not let a batch outlive its claim lease, or another
// replica re-claims rows that are still being published. The relay stops at half the lease and releases
// the rest of the batch.
func TestP16_RelayStopsBeforeTheLeaseExpires(t *testing.T) {
	pool := testenv.Postgres(t)
	ctx := context.Background()
	for i := 0; i < 5; i++ {
		enqueue(t, ctx, pool, topicARN, newEnvelope(t), true)
	}
	relay := outbox.NewRelay(outbox.RelayConfig{
		Pool: pool, Publisher: slowPublisher{150 * time.Millisecond}, BatchSize: 5, PollInterval: time.Millisecond,
		MaxBackoff: time.Second, ClaimLease: 400 * time.Millisecond, Logger: quiet,
	})
	published, err := relay.RunOnce(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if published == 0 || published >= 5 {
		t.Fatalf("want a partial batch, published %d of 5", published)
	}
	if n := testenv.Count(t, pool, "SELECT count(*) FROM outbox WHERE published_at IS NULL AND next_attempt_at <= now()"); n != 5-published {
		t.Fatalf("the rest of the batch must be released for other relays: %d released, want %d", n, 5-published)
	}
}
