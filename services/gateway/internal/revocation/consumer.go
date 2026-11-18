package revocation

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"math"
	"math/rand/v2"
	"net/http"
	"strconv"
	"sync/atomic"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"

	"github.com/ticketpro/gateway/internal/platform/envelope"
	"github.com/ticketpro/gateway/internal/platform/retry"
	"github.com/ticketpro/gateway/internal/platform/tracing"
)

// Queue is the part of the SQS client the consumer uses.
type Queue interface {
	ReceiveMessage(ctx context.Context, in *sqs.ReceiveMessageInput, optFns ...func(*sqs.Options)) (*sqs.ReceiveMessageOutput, error)
	DeleteMessage(ctx context.Context, in *sqs.DeleteMessageInput, optFns ...func(*sqs.Options)) (*sqs.DeleteMessageOutput, error)
	ChangeMessageVisibility(ctx context.Context, in *sqs.ChangeMessageVisibilityInput, optFns ...func(*sqs.Options)) (*sqs.ChangeMessageVisibilityOutput, error)
}

// Outcome is what Process did with one message (RFC 0003 §5.4).
type Outcome int

// Outcomes of Process.
const (
	Succeeded Outcome = iota + 1
	Poison
	Transient
)

// ConsumerConfig configures the TokenRevoked consumer of the queue ticketpro-gateway.
type ConsumerConfig struct {
	Queue     Queue
	QueueURL  string
	Store     Store
	Validator *envelope.Validator
	WaitTimeS int
	// RequestTimeout is the AWS client timeout (HTTP_CLIENT_TIMEOUT_MS); the long poll adds WaitTimeS to it.
	RequestTimeout time.Duration
	Clock          func() time.Time
	Logger         *slog.Logger
}

// Consumer applies TokenRevoked to the blacklist. The gateway has no database, so there is no
// processed_messages table: SET revoked:{jti} with the same TTL bound is idempotent by itself (ADR-0006).
type Consumer struct {
	cfg      ConsumerConfig
	brokerUp atomic.Bool
}

// NewConsumer returns a consumer; the broker is assumed up until a receive fails.
func NewConsumer(cfg ConsumerConfig) *Consumer {
	if cfg.Logger == nil {
		cfg.Logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	if cfg.Clock == nil {
		cfg.Clock = time.Now
	}
	c := &Consumer{cfg: cfg}
	c.brokerUp.Store(true)
	return c
}

// BrokerUp reports whether the last receive succeeded.
func (c *Consumer) BrokerUp() bool { return c.brokerUp.Load() }

// Run receives and processes messages until ctx is cancelled. Receive errors back off and retry.
func (c *Consumer) Run(ctx context.Context) error {
	failures := 0
	for {
		out, err := c.cfg.Queue.ReceiveMessage(ctx, &sqs.ReceiveMessageInput{
			QueueUrl:                    aws.String(c.cfg.QueueURL),
			MaxNumberOfMessages:         10,
			WaitTimeSeconds:             int32(c.cfg.WaitTimeS),
			MessageAttributeNames:       []string{"traceparent"},
			MessageSystemAttributeNames: []sqstypes.MessageSystemAttributeName{sqstypes.MessageSystemAttributeNameApproximateReceiveCount},
		}, c.longPoll)
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			if c.brokerUp.Swap(false) {
				c.cfg.Logger.WarnContext(ctx, "broker_down", "error", err.Error())
			}
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(retry.Delay(failures, retry.DefaultBase, retry.DefaultCap, rand.Float64)):
			}
			failures++
			continue
		}
		if !c.brokerUp.Swap(true) {
			c.cfg.Logger.InfoContext(ctx, "broker_up")
		}
		failures = 0
		for _, m := range out.Messages {
			c.Process(context.WithoutCancel(ctx), m)
		}
	}
}

type tokenRevoked struct {
	JTI       string `json:"jti"`
	ExpiresAt string `json:"expiresAt"`
	Reason    string `json:"reason"`
}

// Process validates one message, stores the jti and acknowledges only after the write. It runs in a
// consumer span that continues the producer's trace and links to it (ADR-0012).
func (c *Consumer) Process(ctx context.Context, msg sqstypes.Message) (outcome Outcome) {
	if tp, ok := msg.MessageAttributes["traceparent"]; ok {
		ctx = tracing.Extract(ctx, aws.ToString(tp.StringValue))
	}
	opts := []trace.SpanStartOption{
		trace.WithSpanKind(trace.SpanKindConsumer),
		trace.WithAttributes(attribute.String("messaging.system", "aws_sqs"), attribute.String("messaging.destination.name", c.cfg.QueueURL)),
	}
	if remote := trace.SpanContextFromContext(ctx); remote.IsValid() {
		opts = append(opts, trace.WithLinks(trace.Link{SpanContext: remote}))
	}
	ctx, span := otel.Tracer("github.com/ticketpro/gateway/internal/revocation").Start(ctx, "process TokenRevoked", opts...)
	defer func() {
		if outcome != Succeeded {
			span.SetStatus(codes.Error, map[Outcome]string{Poison: "poison", Transient: "transient"}[outcome])
		}
		span.End()
	}()
	body := []byte(aws.ToString(msg.Body))
	log := c.cfg.Logger.With("sqsMessageId", aws.ToString(msg.MessageId))

	raw, err := envelope.RawEnvelope(body)
	if err == nil {
		err = c.cfg.Validator.Validate(raw)
	}
	var env envelope.Envelope
	if err == nil {
		env, err = envelope.Unwrap(body)
	}
	var p tokenRevoked
	var expiresAt time.Time
	if err == nil && (env.Type != "TokenRevoked" || env.Version != 1) {
		err = fmt.Errorf("%w: no handler for %s v%d", envelope.ErrUnsupportedVersion, env.Type, env.Version)
	}
	if err == nil {
		err = json.Unmarshal(env.Payload, &p)
	}
	if err == nil {
		expiresAt, err = time.Parse(time.RFC3339Nano, p.ExpiresAt)
	}
	if err != nil {
		span.RecordError(err)
		log.WarnContext(ctx, "message_rejected", "poison", true, "error", err.Error())
		c.changeVisibility(ctx, log, msg, 0) // reach maxReceiveCount fast → DLQ via redrive
		return Poison
	}
	log = log.With("messageId", env.MessageID, "correlationId", env.CorrelationID, "type", env.Type)

	if err := c.cfg.Store.Revoke(ctx, p.JTI, expiresAt, c.cfg.Clock()); err != nil {
		receiveCount, _ := strconv.Atoi(msg.Attributes[string(sqstypes.MessageSystemAttributeNameApproximateReceiveCount)])
		delay := retry.Delay(receiveCount, retry.DefaultBase, retry.DefaultCap, rand.Float64)
		seconds := int32(max(1, math.Ceil(delay.Seconds())))
		span.RecordError(err)
		log.WarnContext(ctx, "message_retry", "error", err.Error(), "receiveCount", receiveCount, "visibilityS", seconds)
		c.changeVisibility(ctx, log, msg, seconds)
		return Transient
	}
	if _, err := c.cfg.Queue.DeleteMessage(ctx, &sqs.DeleteMessageInput{QueueUrl: aws.String(c.cfg.QueueURL), ReceiptHandle: msg.ReceiptHandle}); err != nil {
		log.WarnContext(ctx, "ack_failed", "error", err.Error()) // redelivery rewrites the same key
	}
	log.InfoContext(ctx, "message_consumed", "reason", p.Reason)
	return Succeeded
}

// longPoll gives the receive its own HTTP timeout: the default client one is shorter than WaitTimeS.
func (c *Consumer) longPoll(o *sqs.Options) {
	o.HTTPClient = &http.Client{Timeout: time.Duration(c.cfg.WaitTimeS)*time.Second + c.cfg.RequestTimeout}
}

func (c *Consumer) changeVisibility(ctx context.Context, log *slog.Logger, msg sqstypes.Message, seconds int32) {
	_, err := c.cfg.Queue.ChangeMessageVisibility(ctx, &sqs.ChangeMessageVisibilityInput{
		QueueUrl: aws.String(c.cfg.QueueURL), ReceiptHandle: msg.ReceiptHandle, VisibilityTimeout: seconds,
	})
	if err != nil {
		log.WarnContext(ctx, "visibility_change_failed", "error", err.Error())
	}
}
