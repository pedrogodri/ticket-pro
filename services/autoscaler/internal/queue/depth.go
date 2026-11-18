// Package queue reads SQS queue depth (ADR-0015).
package queue

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"sync"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
)

// API is the part of the SQS client the reader uses.
type API interface {
	GetQueueUrl(ctx context.Context, in *sqs.GetQueueUrlInput, optFns ...func(*sqs.Options)) (*sqs.GetQueueUrlOutput, error)
	GetQueueAttributes(ctx context.Context, in *sqs.GetQueueAttributesInput, optFns ...func(*sqs.Options)) (*sqs.GetQueueAttributesOutput, error)
}

// Reader resolves queue names to URLs once and reads their depth.
type Reader struct {
	api  API
	mu   sync.Mutex
	urls map[string]string
}

// NewReader returns a Reader over an SQS client.
func NewReader(api API) *Reader { return &Reader{api: api, urls: map[string]string{}} }

// Depth is ApproximateNumberOfMessages + ApproximateNumberOfMessagesNotVisible: the backlog including
// messages already being processed, which is the work the replicas have to absorb.
func (r *Reader) Depth(ctx context.Context, name string) (int, error) {
	url, err := r.url(ctx, name)
	if err != nil {
		return 0, err
	}
	out, err := r.api.GetQueueAttributes(ctx, &sqs.GetQueueAttributesInput{
		QueueUrl: aws.String(url),
		AttributeNames: []sqstypes.QueueAttributeName{
			sqstypes.QueueAttributeNameApproximateNumberOfMessages,
			sqstypes.QueueAttributeNameApproximateNumberOfMessagesNotVisible,
		},
	})
	if err != nil {
		var missing *sqstypes.QueueDoesNotExist
		if errors.As(err, &missing) {
			r.forget(name) // recreated queues get a new URL: resolve it again next time
		}
		return 0, err
	}
	total := 0
	for _, k := range []string{"ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible"} {
		raw, ok := out.Attributes[k]
		if !ok {
			continue
		}
		n, err := strconv.Atoi(raw)
		if err != nil {
			return 0, fmt.Errorf("%s of %s: %q is not a number", k, name, raw)
		}
		total += n
	}
	return total, nil
}

func (r *Reader) forget(name string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.urls, name)
}

func (r *Reader) url(ctx context.Context, name string) (string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if u, ok := r.urls[name]; ok {
		return u, nil
	}
	out, err := r.api.GetQueueUrl(ctx, &sqs.GetQueueUrlInput{QueueName: aws.String(name)})
	if err != nil {
		return "", err
	}
	r.urls[name] = aws.ToString(out.QueueUrl)
	return r.urls[name], nil
}
