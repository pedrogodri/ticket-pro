//go:build integration

package messaging_test

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sns"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ticketpro/platform/envelope"
	"github.com/ticketpro/platform/internal/testenv"
	"github.com/ticketpro/platform/messaging"
)

const contractsDir = "../../../contracts"

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

// effectHandler inserts one row in effects per handled message. failFirst makes the first call fail
// transiently; result overrides the return value after the insert.
type effectHandler struct {
	calls     atomic.Int32
	failFirst bool
	result    error
}

func (h *effectHandler) Versions() []int { return []int{1} }

func (h *effectHandler) Handle(ctx context.Context, tx pgx.Tx, env envelope.Envelope) error {
	if h.calls.Add(1) == 1 && h.failFirst {
		return errors.New("database temporarily unavailable")
	}
	if _, err := tx.Exec(ctx, "INSERT INTO effects (message_id) VALUES ($1)", env.MessageID); err != nil {
		return err
	}
	return h.result
}

type fixture struct {
	t        *testing.T
	pool     *pgxpool.Pool
	sqs      *sqs.Client
	sns      *sns.Client
	queueURL string
	dlqURL   string
	consumer *messaging.Consumer
}

func setup(t *testing.T, handlers map[string]messaging.Handler) *fixture {
	t.Helper()
	pool := testenv.Postgres(t)
	cfg, _ := testenv.MiniStack(t)
	f := &fixture{t: t, pool: pool, sqs: sqs.NewFromConfig(cfg), sns: sns.NewFromConfig(cfg)}
	f.queueURL, f.dlqURL = testenv.Queue(t, f.sqs, "consumer", 3)
	validator, err := envelope.NewValidator(contractsDir)
	if err != nil {
		t.Fatal(err)
	}
	f.consumer = messaging.NewConsumer(messaging.ConsumerConfig{
		SQS: f.sqs, QueueURL: f.queueURL, Name: "test-consumer", Pool: pool, Validator: validator,
		Handlers: handlers, Concurrency: 4, WaitTimeS: 1, Logger: quiet,
	})
	return f
}

// example returns contracts/examples/<name>.json with a fresh messageId.
func example(t *testing.T, name, messageID string) string {
	t.Helper()
	b, err := os.ReadFile(contractsDir + "/examples/" + name + ".json")
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	m["messageId"] = messageID
	out, _ := json.Marshal(m)
	return string(out)
}

func (f *fixture) send(body string) {
	f.t.Helper()
	if _, err := f.sqs.SendMessage(context.Background(), &sqs.SendMessageInput{QueueUrl: aws.String(f.queueURL), MessageBody: aws.String(body)}); err != nil {
		f.t.Fatal(err)
	}
}

// receiveAndProcess receives once (long poll) and runs Process on every message.
func (f *fixture) receiveAndProcess(url string) []messaging.Outcome {
	f.t.Helper()
	out, err := f.sqs.ReceiveMessage(context.Background(), &sqs.ReceiveMessageInput{
		QueueUrl: aws.String(url), MaxNumberOfMessages: 10, WaitTimeSeconds: 1,
		MessageAttributeNames:       []string{"All"},
		MessageSystemAttributeNames: []sqstypes.MessageSystemAttributeName{sqstypes.MessageSystemAttributeNameApproximateReceiveCount},
	})
	if err != nil {
		f.t.Fatal(err)
	}
	outcomes := make([]messaging.Outcome, 0, len(out.Messages))
	for _, m := range out.Messages {
		outcomes = append(outcomes, f.consumer.Process(context.Background(), m))
	}
	return outcomes
}

func count(outcomes []messaging.Outcome, want messaging.Outcome) int {
	n := 0
	for _, o := range outcomes {
		if o == want {
			n++
		}
	}
	return n
}

const msgID = "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55"

func TestP09_DuplicateFiveTimesHasOneEffect(t *testing.T) {
	f := setup(t, map[string]messaging.Handler{"OrderCreated": &effectHandler{}})
	body := example(t, "OrderCreated.v1", msgID)
	for i := 0; i < 5; i++ {
		f.send(body)
	}
	var outcomes []messaging.Outcome
	for i := 0; i < 10 && len(outcomes) < 5; i++ {
		outcomes = append(outcomes, f.receiveAndProcess(f.queueURL)...)
	}
	if count(outcomes, messaging.Succeeded) != 1 || count(outcomes, messaging.Duplicate) != 4 {
		t.Fatalf("want 1 Succeeded + 4 Duplicate, got %v", outcomes)
	}
	if n := testenv.Count(t, f.pool, "SELECT count(*) FROM effects"); n != 1 {
		t.Fatalf("effects = %d, want 1", n)
	}
	if n := testenv.Count(t, f.pool, "SELECT count(*) FROM processed_messages"); n != 1 {
		t.Fatalf("processed_messages = %d, want 1", n)
	}
	if d := testenv.QueueDepth(t, f.sqs, f.queueURL); d != 0 {
		t.Fatalf("queue must be empty, depth %d", d)
	}
}

func TestP10_CrashBeforeCommitIsRedelivered(t *testing.T) {
	f := setup(t, map[string]messaging.Handler{"OrderCreated": &effectHandler{failFirst: true}})
	f.send(example(t, "OrderCreated.v1", msgID))

	var outcomes []messaging.Outcome
	deadline := time.Now().Add(20 * time.Second)
	for count(outcomes, messaging.Succeeded) == 0 && time.Now().Before(deadline) {
		outcomes = append(outcomes, f.receiveAndProcess(f.queueURL)...)
	}
	if len(outcomes) < 2 || outcomes[0] != messaging.Transient || outcomes[len(outcomes)-1] != messaging.Succeeded {
		t.Fatalf("want Transient then Succeeded, got %v", outcomes)
	}
	if n := testenv.Count(t, f.pool, "SELECT count(*) FROM effects"); n != 1 {
		t.Fatalf("effects = %d, want 1", n)
	}
}

func TestP11_PoisonGoesToDLQ(t *testing.T) {
	f := setup(t, map[string]messaging.Handler{"OrderCreated": &effectHandler{}})
	invalid := strings.Replace(example(t, "OrderCreated.v1", msgID), `"quantity":2`, `"quantity":99`, 1)
	f.send(invalid)

	for i := 0; i < 6 && testenv.QueueDepth(t, f.sqs, f.dlqURL) == 0; i++ {
		for _, o := range f.receiveAndProcess(f.queueURL) {
			if o != messaging.Poison {
				t.Fatalf("invalid payload: want Poison, got %v", o)
			}
		}
	}
	if d := testenv.QueueDepth(t, f.sqs, f.dlqURL); d != 1 {
		t.Fatalf("message must be in the DLQ, depth %d", d)
	}
	if n := testenv.Count(t, f.pool, "SELECT count(*) FROM effects"); n != 0 {
		t.Fatalf("poison must not have effects, got %d", n)
	}

	// A valid message whose type has no handler is poison too, and is not acknowledged.
	f.send(example(t, "PaymentFailed.v1", "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a56"))
	outcomes := f.receiveAndProcess(f.queueURL)
	if len(outcomes) != 1 || outcomes[0] != messaging.Poison {
		t.Fatalf("type without handler: want [Poison], got %v", outcomes)
	}
	if d := testenv.QueueDepth(t, f.sqs, f.queueURL); d != 1 {
		t.Fatalf("poison must not be acknowledged, depth %d", d)
	}
}

func TestP14_StaleEventIsRecordedAndAcknowledged(t *testing.T) {
	f := setup(t, map[string]messaging.Handler{"OrderCreated": &effectHandler{result: messaging.ErrStale}})
	f.send(example(t, "OrderCreated.v1", msgID))

	outcomes := f.receiveAndProcess(f.queueURL)
	if len(outcomes) != 1 || outcomes[0] != messaging.Stale {
		t.Fatalf("want [Stale], got %v", outcomes)
	}
	if n := testenv.Count(t, f.pool, "SELECT count(*) FROM processed_messages"); n != 1 {
		t.Fatalf("stale must be recorded, processed_messages = %d", n)
	}
	if d := testenv.QueueDepth(t, f.sqs, f.queueURL); d != 0 {
		t.Fatalf("stale must be acknowledged, depth %d", d)
	}
}

func TestP15_RawAndSNSFormatsAreBothConsumed(t *testing.T) {
	f := setup(t, map[string]messaging.Handler{"OrderCreated": &effectHandler{}})
	topic := testenv.Topic(t, f.sns, f.sqs, "formats", f.queueURL)

	var viaTopic envelope.Envelope
	if err := json.Unmarshal([]byte(example(t, "OrderCreated.v1", msgID)), &viaTopic); err != nil {
		t.Fatal(err)
	}
	if err := messaging.NewSNSPublisher(f.sns).Publish(context.Background(), topic, viaTopic, ""); err != nil {
		t.Fatal(err)
	}
	quoted, _ := json.Marshal(example(t, "OrderCreated.v1", "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a57"))
	f.send(`{"Type":"Notification","MessageId":"n-1","TopicArn":"` + topic + `","Message":` + string(quoted) + `}`)

	var outcomes []messaging.Outcome
	for i := 0; i < 10 && len(outcomes) < 2; i++ {
		outcomes = append(outcomes, f.receiveAndProcess(f.queueURL)...)
	}
	if count(outcomes, messaging.Succeeded) != 2 {
		t.Fatalf("want 2 Succeeded (raw + SNS), got %v", outcomes)
	}
}

// Run long-polls for WaitTimeS, which is longer than the HTTP client timeout used for every other call
// (SQS_WAIT_TIME_S=10 vs HTTP_CLIENT_TIMEOUT_MS=3000): the receive must not be cut by that timeout.
func TestRunLongPollLongerThanClientTimeout(t *testing.T) {
	logs := &syncBuffer{}
	h := &effectHandler{}
	f := setup(t, map[string]messaging.Handler{"OrderCreated": h})
	validator, err := envelope.NewValidator(contractsDir)
	if err != nil {
		t.Fatal(err)
	}
	consumer := messaging.NewConsumer(messaging.ConsumerConfig{
		SQS: f.sqs, QueueURL: f.queueURL, Name: "long-poll", Pool: f.pool, Validator: validator,
		Handlers: map[string]messaging.Handler{"OrderCreated": h}, Concurrency: 4,
		WaitTimeS: int(testenv.ClientTimeout/time.Second) + 2, RequestTimeout: testenv.ClientTimeout, Logger: slog.New(slog.NewJSONHandler(logs, nil)),
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = consumer.Run(ctx) }()

	time.Sleep(testenv.ClientTimeout + time.Second) // the first long poll is now past the client timeout
	f.send(example(t, "OrderCreated.v1", msgID))
	deadline := time.Now().Add(15 * time.Second)
	for testenv.Count(t, f.pool, "SELECT count(*) FROM effects") == 0 {
		if time.Now().After(deadline) {
			t.Fatal("message not consumed by Run")
		}
		time.Sleep(200 * time.Millisecond)
	}
	cancel()
	if strings.Contains(logs.String(), "broker_down") {
		t.Fatalf("a long poll longer than the client timeout must not fail: %s", logs.String())
	}
}

type syncBuffer struct {
	mu  sync.Mutex
	buf strings.Builder
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}
