//go:build integration

package reservations_test

import (
	"context"
	"encoding/json"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ticketpro/inventory-service/internal/modules/reservations"
	"github.com/ticketpro/inventory-service/internal/modules/tickets"
	"github.com/ticketpro/inventory-service/internal/platform/envelope"
	"github.com/ticketpro/inventory-service/internal/platform/messaging"
	"github.com/ticketpro/inventory-service/internal/testdb"
)

const (
	topic   = "arn:aws:sns:us-east-1:000000000000:ticketpro-inventory"
	eventID = "0193a6f0-3333-7000-8000-000000000003" // seeded "Festival de Verão 2026"
	userID  = "0193a6f0-1111-7000-8000-000000000001"
)

var validator *envelope.Validator

func setup(t *testing.T) *pgxpool.Pool {
	t.Helper()
	if validator == nil {
		v, err := envelope.NewValidator("../../../contracts")
		if err != nil {
			t.Fatal(err)
		}
		validator = v
	}
	return testdb.Pool(t)
}

// newSector inserts an isolated sector so tests do not share stock.
func newSector(t *testing.T, pool *pgxpool.Pool, capacity int) string {
	t.Helper()
	id := uuid.NewString()
	_, err := pool.Exec(context.Background(), `INSERT INTO sectors (id, event_id, name, capacity, available, unit_price_cents, currency)
		VALUES ($1, $2, 'Teste', $3, $3, 15000, 'BRL')`, id, eventID, capacity)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func handle(t *testing.T, pool *pgxpool.Pool, h messaging.Handler, typ string, payload any) error {
	t.Helper()
	env, err := envelope.New(uuid.NewString(), typ, 1, payload)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := h.Handle(ctx, tx, env); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// outboxFor returns the outbox messages produced for an order, each validated against the contract.
func outboxFor(t *testing.T, pool *pgxpool.Pool, orderID string) map[string]map[string]any {
	t.Helper()
	rows, err := pool.Query(context.Background(),
		`SELECT message_id::text, correlation_id::text, type, version, occurred_at, payload FROM outbox WHERE payload->>'orderId' = $1`, orderID)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	out := map[string]map[string]any{}
	for rows.Next() {
		var env envelope.Envelope
		var payload []byte
		if err := rows.Scan(&env.MessageID, &env.CorrelationID, &env.Type, &env.Version, &env.OccurredAt, &payload); err != nil {
			t.Fatal(err)
		}
		env.Payload = payload
		raw, _ := json.Marshal(env)
		if err := validator.Validate(raw); err != nil {
			t.Fatalf("%s does not match its contract: %v", env.Type, err)
		}
		var p map[string]any
		_ = json.Unmarshal(payload, &p)
		out[env.Type] = p
	}
	return out
}

func handlers() (reservations.OrderCreatedHandler, reservations.OrderCancelledHandler, tickets.OrderConfirmedHandler) {
	return reservations.OrderCreatedHandler{TopicARN: topic, TTL: time.Minute},
		reservations.OrderCancelledHandler{},
		tickets.OrderConfirmedHandler{TopicARN: topic}
}

func orderCreated(orderID, sectorID string, quantity int) map[string]any {
	return map[string]any{"orderId": orderID, "userId": userID, "eventId": eventID, "sectorId": sectorID, "quantity": quantity}
}

func available(t *testing.T, pool *pgxpool.Pool, sectorID string) int {
	t.Helper()
	var n int
	if err := pool.QueryRow(context.Background(), `SELECT available FROM sectors WHERE id = $1`, sectorID).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestOrderCreated_ReservesStockAndPublishesInventoryReserved(t *testing.T) {
	pool := setup(t)
	created, _, _ := handlers()
	sector, order := newSector(t, pool, 5), uuid.NewString()
	if err := handle(t, pool, created, "OrderCreated", orderCreated(order, sector, 2)); err != nil {
		t.Fatal(err)
	}
	if n := available(t, pool, sector); n != 3 {
		t.Fatalf("available %d, want 3", n)
	}
	got := outboxFor(t, pool, order)["InventoryReserved"]
	if got == nil || got["totalCents"] != float64(30000) || got["unitPriceCents"] != float64(15000) || got["currency"] != "BRL" {
		t.Fatalf("InventoryReserved %v", got)
	}
}

func TestOrderCreated_RejectsWithoutStockOrUnknownSector(t *testing.T) {
	pool := setup(t)
	created, _, _ := handlers()
	sector, order := newSector(t, pool, 1), uuid.NewString()
	if err := handle(t, pool, created, "OrderCreated", orderCreated(order, sector, 2)); err != nil {
		t.Fatal(err)
	}
	if r := outboxFor(t, pool, order)["InventoryRejected"]; r == nil || r["reason"] != "OUT_OF_STOCK" {
		t.Fatalf("want OUT_OF_STOCK, got %v", r)
	}
	unknown := uuid.NewString()
	if err := handle(t, pool, created, "OrderCreated", orderCreated(unknown, uuid.NewString(), 1)); err != nil {
		t.Fatal(err)
	}
	if r := outboxFor(t, pool, unknown)["InventoryRejected"]; r == nil || r["reason"] != "SECTOR_NOT_FOUND" {
		t.Fatalf("want SECTOR_NOT_FOUND, got %v", r)
	}
}

func TestOrderConfirmed_IssuesOneTicketPerUnitOnce(t *testing.T) {
	pool := setup(t)
	created, _, confirmed := handlers()
	sector, order := newSector(t, pool, 5), uuid.NewString()
	if err := handle(t, pool, created, "OrderCreated", orderCreated(order, sector, 2)); err != nil {
		t.Fatal(err)
	}
	confirm := map[string]any{"orderId": order, "userId": userID, "eventId": eventID, "sectorId": sector, "quantity": 2,
		"totalCents": 30000, "currency": "BRL", "confirmedAt": "2026-11-20T18:30:14.120Z"}
	if err := handle(t, pool, confirmed, "OrderConfirmed", confirm); err != nil {
		t.Fatal(err)
	}
	issued := outboxFor(t, pool, order)["TicketsIssued"]
	list, _ := issued["tickets"].([]any)
	if len(list) != 2 || issued["eventName"] != "Festival de Verão 2026" || issued["userId"] != userID {
		t.Fatalf("TicketsIssued %v", issued)
	}
	if err := handle(t, pool, confirmed, "OrderConfirmed", confirm); err != messaging.ErrStale {
		t.Fatalf("second confirmation: want ErrStale, got %v", err)
	}
	var n int
	_ = pool.QueryRow(context.Background(), `SELECT count(*) FROM tickets t JOIN reservations r ON r.id = t.reservation_id WHERE r.order_id = $1`, order).Scan(&n)
	if n != 2 {
		t.Fatalf("tickets %d, want 2", n)
	}
}

func TestOrderCancelled_ReturnsExactlyTheReservedQuantity(t *testing.T) {
	pool := setup(t)
	created, cancelled, _ := handlers()
	sector, order := newSector(t, pool, 5), uuid.NewString()
	if err := handle(t, pool, created, "OrderCreated", orderCreated(order, sector, 3)); err != nil {
		t.Fatal(err)
	}
	cancel := map[string]any{"orderId": order, "userId": userID, "reason": "PAYMENT_FAILED", "cancelledAt": "2026-11-20T18:30:20.000Z"}
	if err := handle(t, pool, cancelled, "OrderCancelled", cancel); err != nil {
		t.Fatal(err)
	}
	if n := available(t, pool, sector); n != 5 {
		t.Fatalf("available %d, want 5", n)
	}
	if err := handle(t, pool, cancelled, "OrderCancelled", cancel); err != messaging.ErrStale {
		t.Fatalf("second cancellation: want ErrStale, got %v", err)
	}
}

// Review Focus 5 / R2: 100 concurrent reservations of 1 unit on a 10-unit sector.
func TestR2_HundredConcurrentReservationsOnTenUnits(t *testing.T) {
	pool := setup(t)
	created, _, _ := handlers()
	sector := newSector(t, pool, 10)
	var wg sync.WaitGroup
	errs := make(chan error, 100)
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			errs <- handle(t, pool, created, "OrderCreated", orderCreated(uuid.NewString(), sector, 1))
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	var reserved, rejected int
	_ = pool.QueryRow(context.Background(), `SELECT count(*) FROM outbox WHERE type = 'InventoryReserved' AND payload->>'sectorId' = $1`, sector).Scan(&reserved)
	_ = pool.QueryRow(context.Background(), `SELECT count(*) FROM outbox WHERE type = 'InventoryRejected' AND payload->>'sectorId' = $1 AND payload->>'reason' = 'OUT_OF_STOCK'`, sector).Scan(&rejected)
	if reserved != 10 || rejected != 90 || available(t, pool, sector) != 0 {
		t.Fatalf("reserved %d rejected %d available %d; want 10, 90, 0", reserved, rejected, available(t, pool, sector))
	}
}
