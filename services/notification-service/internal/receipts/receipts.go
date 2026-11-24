// Package receipts stores purchase receipts in S3 and records notifications (TicketsIssued, OrderCancelled).
package receipts

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/ticketpro/notification-service/internal/platform/envelope"
)

// Store writes a receipt object.
type Store interface {
	Put(ctx context.Context, key string, body []byte) error
}

type s3Store struct {
	client *s3.Client
	bucket string
}

// NewS3Store writes receipts to bucket (RECEIPTS_BUCKET).
func NewS3Store(client *s3.Client, bucket string) Store {
	return s3Store{client: client, bucket: bucket}
}

func (s s3Store) Put(ctx context.Context, key string, body []byte) error {
	_, err := s.client.PutObject(ctx, &s3.PutObjectInput{
		Bucket: aws.String(s.bucket), Key: aws.String(key), Body: bytes.NewReader(body), ContentType: aws.String("application/json"),
	})
	if err != nil {
		return fmt.Errorf("put receipt %s: %w", key, err)
	}
	return nil
}

type ticketsIssued struct {
	OrderID       string `json:"orderId"`
	UserID        string `json:"userId"`
	EventName     string `json:"eventName"`
	EventStartsAt string `json:"eventStartsAt"`
	SectorName    string `json:"sectorName"`
	Tickets       []struct {
		TicketID string `json:"ticketId"`
		Code     string `json:"code"`
	} `json:"tickets"`
}

// TicketsIssuedHandler writes receipts/{orderId}.json and records the notification. The S3 write is not
// transactional, so it happens first and its key is deterministic: a redelivery rewrites the same object
// (ADR-0006). An S3 failure is transient and nothing is recorded.
type TicketsIssuedHandler struct {
	Store Store
}

// Versions supported.
func (TicketsIssuedHandler) Versions() []int { return []int{1} }

// Handle applies one TicketsIssued.
func (h TicketsIssuedHandler) Handle(ctx context.Context, tx pgx.Tx, env envelope.Envelope) error {
	var in ticketsIssued
	if err := json.Unmarshal(env.Payload, &in); err != nil {
		return fmt.Errorf("%w: %v", envelope.ErrInvalidMessage, err)
	}
	receipt, err := json.Marshal(map[string]any{
		"orderId": in.OrderID, "userId": in.UserID, "eventName": in.EventName, "eventStartsAt": in.EventStartsAt,
		"sectorName": in.SectorName, "tickets": in.Tickets, "issuedAt": env.OccurredAt.UTC().Format("2006-01-02T15:04:05.000Z"),
	})
	if err != nil {
		return err
	}
	if err := h.Store.Put(ctx, "receipts/"+in.OrderID+".json", receipt); err != nil {
		return err
	}
	return record(ctx, tx, in.OrderID, "TICKETS_ISSUED", receipt)
}

// OrderCancelledHandler records the cancellation notification.
type OrderCancelledHandler struct{}

// Versions supported.
func (OrderCancelledHandler) Versions() []int { return []int{1} }

// Handle applies one OrderCancelled.
func (OrderCancelledHandler) Handle(ctx context.Context, tx pgx.Tx, env envelope.Envelope) error {
	var in struct {
		OrderID string `json:"orderId"`
	}
	if err := json.Unmarshal(env.Payload, &in); err != nil {
		return fmt.Errorf("%w: %v", envelope.ErrInvalidMessage, err)
	}
	return record(ctx, tx, in.OrderID, "ORDER_CANCELLED", env.Payload)
}

func record(ctx context.Context, tx pgx.Tx, orderID, kind string, payload []byte) error {
	_, err := tx.Exec(ctx, `INSERT INTO notifications (id, order_id, kind, payload) VALUES ($1, $2, $3, $4)
		ON CONFLICT (order_id, kind) DO NOTHING`, uuid.NewString(), orderID, kind, string(payload))
	return err
}
