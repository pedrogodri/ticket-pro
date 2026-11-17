//go:build integration

package reservations_test

import (
	"context"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ticketpro/inventory-service/internal/modules/reservations"
	"github.com/ticketpro/inventory-service/internal/modules/tickets"
	"github.com/ticketpro/inventory-service/internal/platform/messaging"
)

// reserve creates a pending reservation through the real handler, then moves its deadline.
func reserve(t *testing.T, pool *pgxpool.Pool, sector string, quantity int, expiresIn time.Duration) string {
	t.Helper()
	created, _, _ := handlers()
	order := uuid.NewString()
	if err := handle(t, pool, created, "OrderCreated", orderCreated(order, sector, quantity)); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(context.Background(), `UPDATE reservations SET expires_at = now() + $2::interval WHERE order_id = $1`,
		order, expiresIn.String()); err != nil {
		t.Fatal(err)
	}
	return order
}

func status(t *testing.T, pool *pgxpool.Pool, order string) string {
	t.Helper()
	var s string
	if err := pool.QueryRow(context.Background(), `SELECT status FROM reservations WHERE order_id = $1`, order).Scan(&s); err != nil {
		t.Fatal(err)
	}
	return s
}

func expirer(pool *pgxpool.Pool, batch int) reservations.Expirer {
	return reservations.Expirer{Pool: pool, TopicARN: topic, Interval: time.Second, BatchSize: batch}
}

// expireAll drains every expired reservation (other tests share the database).
func expireAll(t *testing.T, e reservations.Expirer) int {
	t.Helper()
	total := 0
	for {
		n, err := e.RunOnce(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		total += n
		if n == 0 {
			return total
		}
	}
}

func TestExpirer_ReleasesExpiredPendingReservation(t *testing.T) {
	pool := setup(t)
	sector := newSector(t, pool, 5)
	order := reserve(t, pool, sector, 2, -time.Second)
	expireAll(t, expirer(pool, 50))
	if s := status(t, pool, order); s != "RELEASED" {
		t.Fatalf("status %s", s)
	}
	if n := available(t, pool, sector); n != 5 {
		t.Fatalf("available %d, want 5", n)
	}
	got := outboxFor(t, pool, order)["InventoryReservationExpired"]
	if got == nil || got["quantity"] != float64(2) || got["reservationId"] == "" {
		t.Fatalf("outbox %v", got)
	}
}

func TestExpirer_LeavesFreshAndConfirmedReservations(t *testing.T) {
	pool := setup(t)
	_, _, confirmed := handlers()
	sector := newSector(t, pool, 5)
	fresh := reserve(t, pool, sector, 1, time.Hour)
	done := reserve(t, pool, sector, 1, time.Hour)
	if err := handle(t, pool, confirmed, "OrderConfirmed", confirmation(done, sector, 1)); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(context.Background(), `UPDATE reservations SET expires_at = now() - interval '1 second' WHERE order_id = $1`, done); err != nil {
		t.Fatal(err)
	}
	expireAll(t, expirer(pool, 50))
	if status(t, pool, fresh) != "PENDING" || status(t, pool, done) != "CONFIRMED" || available(t, pool, sector) != 3 {
		t.Fatalf("fresh %s confirmed %s available %d", status(t, pool, fresh), status(t, pool, done), available(t, pool, sector))
	}
}

// Review Focus 1: replicas scanning at the same time release each reservation once.
func TestExpirer_ConcurrentScansReleaseOnce(t *testing.T) {
	pool := setup(t)
	sector := newSector(t, pool, 10)
	for range 10 {
		reserve(t, pool, sector, 1, -time.Second)
	}
	var wg sync.WaitGroup
	for range 4 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			e := expirer(pool, 3)
			for {
				if n, err := e.RunOnce(context.Background()); err != nil || n == 0 {
					return
				}
			}
		}()
	}
	wg.Wait()
	if n := available(t, pool, sector); n != 10 {
		t.Fatalf("available %d, want 10 (each reservation released once)", n)
	}
}

func TestExpirer_RespectsBatchSize(t *testing.T) {
	pool := setup(t)
	expireAll(t, expirer(pool, 50))
	sector := newSector(t, pool, 5)
	for range 3 {
		reserve(t, pool, sector, 1, -time.Second)
	}
	if n, err := expirer(pool, 2).RunOnce(context.Background()); err != nil || n != 2 {
		t.Fatalf("released %d err %v, want 2", n, err)
	}
}

// Review Focus 2: the stock comes back once whichever compensation arrives first.
func TestOrderCancelled_AfterExpiryIsStale(t *testing.T) {
	pool := setup(t)
	_, cancelled, _ := handlers()
	sector := newSector(t, pool, 5)
	order := reserve(t, pool, sector, 2, -time.Second)
	expireAll(t, expirer(pool, 50))
	cancel := map[string]any{"orderId": order, "userId": userID, "reason": "RESERVATION_EXPIRED", "cancelledAt": "2026-11-20T18:30:20.000Z"}
	if err := handle(t, pool, cancelled, "OrderCancelled", cancel); err != messaging.ErrStale {
		t.Fatalf("want ErrStale, got %v", err)
	}
	if n := available(t, pool, sector); n != 5 {
		t.Fatalf("available %d, want 5", n)
	}
}

func TestOrderCancelled_AfterConfirmationIsStale(t *testing.T) {
	pool := setup(t)
	_, cancelled, confirmed := handlers()
	sector := newSector(t, pool, 5)
	order := reserve(t, pool, sector, 2, time.Hour)
	if err := handle(t, pool, confirmed, "OrderConfirmed", confirmation(order, sector, 2)); err != nil {
		t.Fatal(err)
	}
	cancel := map[string]any{"orderId": order, "userId": userID, "reason": "PAYMENT_TIMEOUT", "cancelledAt": "2026-11-20T18:30:20.000Z"}
	if err := handle(t, pool, cancelled, "OrderCancelled", cancel); err != messaging.ErrStale {
		t.Fatalf("want ErrStale, got %v", err)
	}
	if n := available(t, pool, sector); n != 3 {
		t.Fatalf("available %d, want 3", n)
	}
}

func confirmation(order, sector string, quantity int) map[string]any {
	return map[string]any{"orderId": order, "userId": userID, "eventId": eventID, "sectorId": sector, "quantity": quantity,
		"totalCents": 15000 * quantity, "currency": "BRL", "confirmedAt": "2026-11-20T18:30:14.120Z"}
}

// Replicas whose batches touch the same sectors in different orders must not deadlock (40P01).
func TestExpirer_ConcurrentScansAcrossSectorsDoNotDeadlock(t *testing.T) {
	pool := setup(t)
	expireAll(t, expirer(pool, 50))
	sectors := []string{newSector(t, pool, 50), newSector(t, pool, 50), newSector(t, pool, 50), newSector(t, pool, 50)}
	for i := range 80 {
		// Interleave sectors and deadlines so each batch walks the sectors in a different order.
		reserve(t, pool, sectors[(i*3)%len(sectors)], 1, -time.Duration(80-i)*time.Millisecond)
	}
	errs := make(chan error, 8)
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			e := expirer(pool, 7)
			for {
				n, err := e.RunOnce(context.Background())
				if err != nil {
					errs <- err
					return
				}
				if n == 0 {
					return
				}
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Errorf("scan failed: %v", err)
	}
	for _, s := range sectors {
		if n := available(t, pool, s); n != 50 {
			t.Errorf("sector %s available %d, want 50", s, n)
		}
	}
}

// Review Focus 5: a failing scan is logged and Run keeps going until ctx is cancelled.
func TestExpirer_RunSurvivesFailingScans(t *testing.T) {
	pool := setup(t)
	broken, err := pgxpool.New(context.Background(), pool.Config().ConnString())
	if err != nil {
		t.Fatal(err)
	}
	broken.Close() // every Begin fails
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	e := reservations.Expirer{Pool: broken, TopicARN: topic, Interval: 20 * time.Millisecond, BatchSize: 10}
	if err := e.Run(ctx); err != context.DeadlineExceeded {
		t.Fatalf("Run returned %v before ctx ended", err)
	}
}

// A confirmation that finds the reservation already released breaks RFC 0003 decision 4: it must be
// visible as an ERROR, not only as a stale event.
func TestOrderConfirmed_AfterReleaseLogsInvariantViolation(t *testing.T) {
	pool := setup(t)
	sector := newSector(t, pool, 5)
	order := reserve(t, pool, sector, 2, -time.Second)
	expireAll(t, expirer(pool, 50))
	var logs strings.Builder
	h := tickets.OrderConfirmedHandler{TopicARN: topic, Logger: slog.New(slog.NewJSONHandler(&logs, nil))}
	if err := handle(t, pool, h, "OrderConfirmed", confirmation(order, sector, 2)); err != messaging.ErrStale {
		t.Fatalf("want ErrStale, got %v", err)
	}
	if !strings.Contains(logs.String(), `"level":"ERROR"`) || !strings.Contains(logs.String(), "order_confirmed_after_release") ||
		!strings.Contains(logs.String(), order) {
		t.Fatalf("logs %s", logs.String())
	}
}

// Cancellation and expiry racing for the same reservations return the stock exactly once.
func TestOrderCancelledRacingExpiryReturnsStockOnce(t *testing.T) {
	pool := setup(t)
	expireAll(t, expirer(pool, 50))
	_, cancelled, _ := handlers()
	sector := newSector(t, pool, 40)
	orders := make([]string, 20)
	for i := range orders {
		orders[i] = reserve(t, pool, sector, 2, -time.Second)
	}
	var wg sync.WaitGroup
	for _, o := range orders {
		wg.Add(1)
		go func(o string) {
			defer wg.Done()
			cancel := map[string]any{"orderId": o, "userId": userID, "reason": "PAYMENT_FAILED", "cancelledAt": "2026-11-20T18:30:20.000Z"}
			if err := handle(t, pool, cancelled, "OrderCancelled", cancel); err != nil && err != messaging.ErrStale {
				t.Errorf("cancel %s: %v", o, err)
			}
		}(o)
	}
	for range 3 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				n, err := expirer(pool, 4).RunOnce(context.Background())
				if err != nil {
					t.Errorf("expire: %v", err)
					return
				}
				if n == 0 {
					return
				}
			}
		}()
	}
	wg.Wait()
	if n := available(t, pool, sector); n != 40 {
		t.Fatalf("available %d, want 40 (stock returned once per reservation)", n)
	}
}

// A backlog of full batches pauses BatchPause between them and still drains; cancellation stops the drain.
func TestExpirer_RunPausesBetweenFullBatches(t *testing.T) {
	pool := setup(t)
	expireAll(t, expirer(pool, 50))
	sector := newSector(t, pool, 10)
	for range 6 {
		reserve(t, pool, sector, 1, -time.Second)
	}
	e := reservations.Expirer{Pool: pool, TopicARN: topic, Interval: 10 * time.Millisecond, BatchSize: 2, BatchPause: 150 * time.Millisecond}
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	_ = e.Run(ctx)
	if n := available(t, pool, sector); n == 10 {
		t.Fatal("drained 3 full batches in 200ms: no pause between batches")
	}
	ctx2, cancel2 := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel2()
	_ = e.Run(ctx2)
	if n := available(t, pool, sector); n != 10 {
		t.Fatalf("available %d, want 10 after draining", n)
	}
}
