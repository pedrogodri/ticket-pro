// Package envelope defines the message envelope shared by every service (RFC 0001 §5.1)
// and how it is read from SQS bodies (SNS notification or raw delivery, §5.2).
package envelope

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// ErrPoison marks a message that can never be processed; consumers send it to the DLQ (RFC 0003 §5.4).
var ErrPoison = errors.New("poison message")

// ErrUnsupportedVersion: the type/version pair has no schema (or no handler). It is poison.
var ErrUnsupportedVersion = fmt.Errorf("%w: unsupported type or version", ErrPoison)

// ErrInvalidMessage: the message violates its schema. It is poison.
var ErrInvalidMessage = fmt.Errorf("%w: invalid message", ErrPoison)

const occurredAtLayout = "2006-01-02T15:04:05.000Z"

// Envelope is the six-field message envelope. Payload holds the raw JSON object.
type Envelope struct {
	MessageID     string
	CorrelationID string
	Type          string
	Version       int
	OccurredAt    time.Time
	Payload       json.RawMessage
}

type wire struct {
	MessageID     string          `json:"messageId"`
	CorrelationID string          `json:"correlationId"`
	Type          string          `json:"type"`
	Version       int             `json:"version"`
	OccurredAt    string          `json:"occurredAt"`
	Payload       json.RawMessage `json:"payload"`
}

// New builds an envelope with a fresh UUID v7 messageId and occurredAt = now (UTC, millisecond precision).
func New(correlationID, typ string, version int, payload any) (Envelope, error) {
	id, err := uuid.NewV7()
	if err != nil {
		return Envelope{}, fmt.Errorf("generate messageId: %w", err)
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return Envelope{}, fmt.Errorf("marshal payload: %w", err)
	}
	return Envelope{
		MessageID:     id.String(),
		CorrelationID: correlationID,
		Type:          typ,
		Version:       version,
		OccurredAt:    time.Now().UTC().Truncate(time.Millisecond),
		Payload:       body,
	}, nil
}

// MarshalJSON writes exactly the six envelope keys, occurredAt as RFC 3339 UTC with milliseconds.
func (e Envelope) MarshalJSON() ([]byte, error) {
	return json.Marshal(wire{
		MessageID:     e.MessageID,
		CorrelationID: e.CorrelationID,
		Type:          e.Type,
		Version:       e.Version,
		OccurredAt:    e.OccurredAt.UTC().Format(occurredAtLayout),
		Payload:       e.Payload,
	})
}

// UnmarshalJSON reads the six envelope keys.
func (e *Envelope) UnmarshalJSON(data []byte) error {
	var w wire
	if err := json.Unmarshal(data, &w); err != nil {
		return err
	}
	occurredAt, err := time.Parse(time.RFC3339Nano, w.OccurredAt)
	if err != nil {
		return fmt.Errorf("occurredAt: %w", err)
	}
	*e = Envelope{w.MessageID, w.CorrelationID, w.Type, w.Version, occurredAt, w.Payload}
	return nil
}

type snsNotification struct {
	Type    string  `json:"Type"`
	Message *string `json:"Message"`
}

// Unwrap returns the raw envelope JSON from an SQS body: the SNS "Message" when the body is an SNS
// notification, otherwise the body itself. Any failure satisfies errors.Is(err, ErrPoison).
func Unwrap(body []byte) (Envelope, error) {
	raw, err := RawEnvelope(body)
	if err != nil {
		return Envelope{}, err
	}
	var env Envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		return Envelope{}, fmt.Errorf("%w: envelope: %v", ErrPoison, err)
	}
	if env.MessageID == "" || env.Type == "" || env.Version < 1 {
		return Envelope{}, fmt.Errorf("%w: envelope without messageId/type/version", ErrPoison)
	}
	return env, nil
}

// RawEnvelope returns the envelope bytes carried by an SQS body (SNS notification or raw delivery).
func RawEnvelope(body []byte) ([]byte, error) {
	trimmed := bytes.TrimSpace(body)
	if len(trimmed) == 0 || trimmed[0] != '{' {
		return nil, fmt.Errorf("%w: body is not a JSON object", ErrPoison)
	}
	var n snsNotification
	if err := json.Unmarshal(trimmed, &n); err != nil {
		return nil, fmt.Errorf("%w: body: %v", ErrPoison, err)
	}
	if n.Type == "Notification" && n.Message != nil {
		return []byte(*n.Message), nil
	}
	return trimmed, nil
}
