// Package reservations reserves and releases stock for orders (R2, ADR-0007).
package reservations

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/ticketpro/inventory-service/internal/platform/envelope"
	"github.com/ticketpro/inventory-service/internal/platform/messaging"
	"github.com/ticketpro/inventory-service/internal/platform/outbox"
)

const timeLayout = "2006-01-02T15:04:05.000Z"

// orderCreated is the internal model: OrderCreated v2. Older versions are upcast to it, so there is
// a single processing path (RFC 0001 §5.4).
type orderCreated struct {
	OrderID  string `json:"orderId"`
	EventID  string `json:"eventId"`
	SectorID string `json:"sectorId"`
	Quantity int    `json:"quantity"`
	Channel  string `json:"channel"`
}

// upcastOrderCreated reads v1 or v2 into the v2 model. v1 had no channel: every order came from the API.
func upcastOrderCreated(env envelope.Envelope) (orderCreated, error) {
	var in orderCreated
	if err := json.Unmarshal(env.Payload, &in); err != nil {
		return orderCreated{}, fmt.Errorf("%w: %v", envelope.ErrInvalidMessage, err)
	}
	switch env.Version {
	case 1:
		in.Channel = "API"
	case 2:
	default:
		return orderCreated{}, fmt.Errorf("%w: OrderCreated v%d", envelope.ErrUnsupportedVersion, env.Version)
	}
	return in, nil
}

// OrderCreatedHandler reserves stock with a conditional UPDATE and answers InventoryReserved or
// InventoryRejected in the same transaction (arquitetura §6 step 2).
type OrderCreatedHandler struct {
	TopicARN string
	TTL      time.Duration // RESERVATION_TTL_MS
}

// Versions supported: v1 through the upcaster, v2 natively.
func (OrderCreatedHandler) Versions() []int { return []int{1, 2} }

// Handle applies one OrderCreated.
func (h OrderCreatedHandler) Handle(ctx context.Context, tx pgx.Tx, env envelope.Envelope) error {
	in, err := upcastOrderCreated(env)
	if err != nil {
		return err
	}
	reject := func(reason string) error {
		return enqueue(ctx, tx, h.TopicARN, env.CorrelationID, "InventoryRejected", map[string]any{
			"orderId": in.OrderID, "eventId": in.EventID, "sectorId": in.SectorID, "quantity": in.Quantity, "reason": reason,
		})
	}
	if in.Quantity < 1 {
		return reject("INVALID_QUANTITY")
	}

	// R2: the decrement only happens when enough stock is left; concurrent buyers serialize on the row.
	var unitPrice int64
	var currency string
	err = tx.QueryRow(ctx, `UPDATE sectors SET available = available - $1
		WHERE id = $2 AND event_id = $3 AND available >= $1
		RETURNING unit_price_cents, currency`, in.Quantity, in.SectorID, in.EventID).Scan(&unitPrice, &currency)
	if errors.Is(err, pgx.ErrNoRows) {
		var exists bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM sectors WHERE id = $1 AND event_id = $2)`,
			in.SectorID, in.EventID).Scan(&exists); err != nil {
			return err
		}
		if !exists {
			return reject("SECTOR_NOT_FOUND")
		}
		return reject("OUT_OF_STOCK")
	}
	if err != nil {
		return err
	}

	reservationID, expiresAt := uuid.NewString(), time.Now().UTC().Add(h.TTL)
	total := unitPrice * int64(in.Quantity)
	if _, err := tx.Exec(ctx, `INSERT INTO reservations (id, order_id, sector_id, quantity, unit_price_cents, total_cents, status, expires_at, channel)
		VALUES ($1, $2, $3, $4, $5, $6, 'PENDING', $7, $8)`,
		reservationID, in.OrderID, in.SectorID, in.Quantity, unitPrice, total, expiresAt, in.Channel); err != nil {
		return err
	}
	return enqueue(ctx, tx, h.TopicARN, env.CorrelationID, "InventoryReserved", map[string]any{
		"orderId": in.OrderID, "reservationId": reservationID, "eventId": in.EventID, "sectorId": in.SectorID,
		"quantity": in.Quantity, "unitPriceCents": unitPrice, "totalCents": total, "currency": currency,
		"expiresAt": expiresAt.Format(timeLayout),
	})
}

// OrderCancelledHandler returns exactly the reserved quantity (compensation, arquitetura §6 step 7).
type OrderCancelledHandler struct{}

// Versions supported.
func (OrderCancelledHandler) Versions() []int { return []int{1} }

// Handle applies one OrderCancelled; no pending reservation is a no-op (stale).
func (OrderCancelledHandler) Handle(ctx context.Context, tx pgx.Tx, env envelope.Envelope) error {
	var in struct {
		OrderID string `json:"orderId"`
	}
	if err := json.Unmarshal(env.Payload, &in); err != nil {
		return fmt.Errorf("%w: %v", envelope.ErrInvalidMessage, err)
	}
	tag, err := tx.Exec(ctx, `WITH released AS (
			UPDATE reservations SET status = 'RELEASED' WHERE order_id = $1 AND status = 'PENDING'
			RETURNING sector_id, quantity)
		UPDATE sectors s SET available = s.available + r.quantity FROM released r WHERE s.id = r.sector_id`, in.OrderID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return messaging.ErrStale
	}
	return nil
}

func enqueue(ctx context.Context, tx pgx.Tx, topic, correlationID, typ string, payload map[string]any) error {
	env, err := envelope.New(correlationID, typ, 1, payload)
	if err != nil {
		return err
	}
	return outbox.Enqueue(ctx, tx, topic, env)
}
