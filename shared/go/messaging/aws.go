// Package messaging publishes envelopes to SNS and consumes them from SQS with the idempotent
// algorithm of RFC 0003 §5.4.
package messaging

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/sns"
	snstypes "github.com/aws/aws-sdk-go-v2/service/sns/types"

	"github.com/ticketpro/platform/envelope"
)

// NewAWSConfig loads the AWS config from the environment (AWS_ENDPOINT_URL, AWS_REGION, credentials)
// with SDK retries disabled — our own backoff owns retrying (RFC 0003 §5.5) — and an HTTP timeout.
func NewAWSConfig(ctx context.Context, timeout time.Duration, optFns ...func(*config.LoadOptions) error) (aws.Config, error) {
	opts := append([]func(*config.LoadOptions) error{
		config.WithRetryMaxAttempts(1),
		config.WithHTTPClient(&http.Client{Timeout: timeout}),
	}, optFns...)
	cfg, err := config.LoadDefaultConfig(ctx, opts...)
	if err != nil {
		return aws.Config{}, fmt.Errorf("load aws config: %w", err)
	}
	return cfg, nil
}

// SNSPublisher publishes envelopes as the SNS Message, duplicating type, version, correlationId and
// traceparent as message attributes (RFC 0001 §5.2).
type SNSPublisher struct{ client *sns.Client }

// NewSNSPublisher returns a publisher; it implements outbox.Publisher.
func NewSNSPublisher(client *sns.Client) *SNSPublisher { return &SNSPublisher{client: client} }

// Publish sends env to topicARN. An empty traceparent is omitted: SNS rejects empty String attributes.
func (p *SNSPublisher) Publish(ctx context.Context, topicARN string, env envelope.Envelope, traceparent string) error {
	body, err := env.MarshalJSON()
	if err != nil {
		return fmt.Errorf("marshal envelope: %w", err)
	}
	attrs := map[string]snstypes.MessageAttributeValue{
		"type":          stringAttr(env.Type),
		"version":       stringAttr(strconv.Itoa(env.Version)),
		"correlationId": stringAttr(env.CorrelationID),
	}
	if traceparent != "" {
		attrs["traceparent"] = stringAttr(traceparent)
	}
	_, err = p.client.Publish(ctx, &sns.PublishInput{
		TopicArn:          aws.String(topicARN),
		Message:           aws.String(string(body)),
		MessageAttributes: attrs,
	})
	if err != nil {
		return fmt.Errorf("publish %s to %s: %w", env.Type, topicARN, err)
	}
	return nil
}

func stringAttr(v string) snstypes.MessageAttributeValue {
	return snstypes.MessageAttributeValue{DataType: aws.String("String"), StringValue: aws.String(v)}
}
