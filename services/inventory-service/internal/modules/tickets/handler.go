// Package tickets issues one ticket per reserved unit when an order is confirmed (arquitetura §6 step 6).
package tickets

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"math/big"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/ticketpro/inventory-service/internal/platform/envelope"
	"github.com/ticketpro/inventory-service/internal/platform/messaging"
	"github.com/ticketpro/inventory-service/internal/platform/outbox"
)

// codeAlphabet avoids look-alike characters (O/0, I/1).
const codeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

// NewCode returns a ticket code formatted XXXX-XXXX-XXXX from crypto/rand.
func NewCode() (string, error) {
	b := make([]byte, 0, 14)
	for i := 0; i < 12; i++ {
		if i > 0 && i%4 == 0 {
			b = append(b, '-')
		}
		n, err := rand.Int(rand.Reader, big.NewInt(int64(len(codeAlphabet))))
		if err != nil {
			return "", err
		}
		b = append(b, codeAlphabet[n.Int64()])
	}
	return string(b), nil
}

// OrderConfirmedHandler confirms the reservation, issues tickets and publishes TicketsIssued.
type OrderConfirmedHandler struct {
	TopicARN string
}

// Versions supported.
func (OrderConfirmedHandler) Versions() []int { return []int{1} }

// Handle applies one OrderConfirmed; a reservation that is not pending is stale (no extra tickets).
func (h OrderConfirmedHandler) Handle(ctx context.Context, tx pgx.Tx, env envelope.Envelope) error {
	var in struct {
		OrderID string `json:"orderId"`
		UserID  string `json:"userId"`
	}
	if err := json.Unmarshal(env.Payload, &in); err != nil {
		return fmt.Errorf("%w: %v", envelope.ErrInvalidMessage, err)
	}
	var reservationID, sectorID, sectorName, eventID, eventName string
	var quantity int
	var startsAt time.Time
	err := tx.QueryRow(ctx, `UPDATE reservations r SET status = 'CONFIRMED'
		FROM sectors s JOIN events e ON e.id = s.event_id
		WHERE r.order_id = $1 AND r.status = 'PENDING' AND s.id = r.sector_id
		RETURNING r.id::text, r.quantity, s.id::text, s.name, e.id::text, e.name, e.starts_at`, in.OrderID).
		Scan(&reservationID, &quantity, &sectorID, &sectorName, &eventID, &eventName, &startsAt)
	if err == pgx.ErrNoRows {
		return messaging.ErrStale
	}
	if err != nil {
		return err
	}
	issued := make([]map[string]string, 0, quantity)
	for i := 0; i < quantity; i++ {
		code, err := NewCode()
		if err != nil {
			return err
		}
		ticketID := uuid.NewString()
		if _, err := tx.Exec(ctx, `INSERT INTO tickets (id, reservation_id, code) VALUES ($1, $2, $3)`,
			ticketID, reservationID, code); err != nil {
			return err // a code collision aborts the transaction; the message is retried (transient)
		}
		issued = append(issued, map[string]string{"ticketId": ticketID, "code": code})
	}
	out, err := envelope.New(env.CorrelationID, "TicketsIssued", 1, map[string]any{
		"orderId": in.OrderID, "userId": in.UserID, "eventId": eventID, "eventName": eventName,
		"eventStartsAt": startsAt.UTC().Format("2006-01-02T15:04:05.000Z"), "sectorId": sectorID,
		"sectorName": sectorName, "tickets": issued,
	})
	if err != nil {
		return err
	}
	return outbox.Enqueue(ctx, tx, h.TopicARN, out)
}
