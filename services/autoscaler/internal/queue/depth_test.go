package queue

import (
	"context"
	"errors"
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
	sqstypes "github.com/aws/aws-sdk-go-v2/service/sqs/types"
)

type fakeSQS struct {
	urlCalls int
	attrs    map[string]string
	err      error
}

func (f *fakeSQS) GetQueueUrl(_ context.Context, in *sqs.GetQueueUrlInput, _ ...func(*sqs.Options)) (*sqs.GetQueueUrlOutput, error) {
	f.urlCalls++
	return &sqs.GetQueueUrlOutput{QueueUrl: aws.String("http://ministack:4566/000000000000/" + aws.ToString(in.QueueName))}, f.err
}

func (f *fakeSQS) GetQueueAttributes(context.Context, *sqs.GetQueueAttributesInput, ...func(*sqs.Options)) (*sqs.GetQueueAttributesOutput, error) {
	return &sqs.GetQueueAttributesOutput{Attributes: f.attrs}, f.err
}

func TestDepthIsVisiblePlusInFlightAndTheURLIsCached(t *testing.T) {
	f := &fakeSQS{attrs: map[string]string{"ApproximateNumberOfMessages": "7", "ApproximateNumberOfMessagesNotVisible": "3"}}
	r := NewReader(f)
	for range 2 {
		n, err := r.Depth(context.Background(), "ticketpro-payment-service")
		if err != nil || n != 10 {
			t.Fatalf("depth %d err %v, want 10", n, err)
		}
	}
	if f.urlCalls != 1 {
		t.Fatalf("GetQueueUrl called %d times, want 1 (cached)", f.urlCalls)
	}
}

func TestDepthReportsErrors(t *testing.T) {
	if _, err := NewReader(&fakeSQS{err: errors.New("down")}).Depth(context.Background(), "q"); err == nil {
		t.Fatal("want error")
	}
	bad := &fakeSQS{attrs: map[string]string{"ApproximateNumberOfMessages": "x"}}
	if _, err := NewReader(bad).Depth(context.Background(), "q"); err == nil {
		t.Fatal("want error on a non-numeric attribute")
	}
}

// A recreated queue (MiniStack restart + infra-init) gets a new URL: drop the cached one on
// QueueDoesNotExist so the next check resolves it again instead of failing forever.
func TestMissingQueueDropsTheCachedURL(t *testing.T) {
	f := &fakeSQS{attrs: map[string]string{"ApproximateNumberOfMessages": "1"}}
	r := NewReader(f)
	if _, err := r.Depth(context.Background(), "q"); err != nil {
		t.Fatal(err)
	}
	f.err = &sqstypes.QueueDoesNotExist{Message: aws.String("gone")}
	if _, err := r.Depth(context.Background(), "q"); err == nil {
		t.Fatal("want the error")
	}
	f.err = nil
	if _, err := r.Depth(context.Background(), "q"); err != nil {
		t.Fatal(err)
	}
	if f.urlCalls != 2 {
		t.Fatalf("GetQueueUrl called %d times, want 2 (re-resolved after QueueDoesNotExist)", f.urlCalls)
	}
}
