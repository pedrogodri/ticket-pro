package revocation

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/redis/go-redis/v9"

	"github.com/ticketpro/gateway/internal/platform/envelope"
)

type fakeQueue struct {
	mu         sync.Mutex
	deleted    int
	visibility []int32
}

func (q *fakeQueue) ReceiveMessage(context.Context, *sqs.ReceiveMessageInput, ...func(*sqs.Options)) (*sqs.ReceiveMessageOutput, error) {
	return &sqs.ReceiveMessageOutput{}, nil
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
