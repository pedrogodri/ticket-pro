package reservations

import (
	"context"
	"io"
	"log/slog"
	"slices"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Expirer is the safety net of arquitetura §6 step 9: it releases PENDING reservations past expires_at
// and publishes InventoryReservationExpired, which the order treats as a cancellation. It is safe in N
// replicas because each batch locks its rows with FOR UPDATE SKIP LOCKED.
type Expirer struct {
	Pool      *pgxpool.Pool // worker role (DATABASE_WORKER_URL)
	TopicARN  string
	Interval  time.Duration // RESERVATION_EXPIRY_SCAN_INTERVAL_MS
	BatchSize int           // RESERVATION_EXPIRY_BATCH_SIZE
	// BatchPause separates full batches (RESERVATION_EXPIRY_BATCH_PAUSE_MS), so a large backlog does not
	// hold a worker connection back to back.
	BatchPause time.Duration
	Logger     *slog.Logger
}

// Run scans every Interval until ctx is cancelled. A failed scan is logged and retried on the next tick.
func (e Expirer) Run(ctx context.Context) error {
	logger := e.Logger
	if logger == nil {
		logger = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	ticker := time.NewTicker(e.Interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
		for {
			n, err := e.RunOnce(ctx)
			if err != nil {
				if ctx.Err() == nil {
					logger.WarnContext(ctx, "reservation_expiry_failed", "error", err.Error())
				}
				break
			}
			if n > 0 {
				logger.InfoContext(ctx, "reservations_expired", "count", n)
			}
			if n < e.BatchSize {
				break
			}
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(e.BatchPause):
			}
		}
	}
}

// RunOnce releases up to BatchSize expired reservations in one transaction and returns how many.
func (e Expirer) RunOnce(ctx context.Context) (int, error) {
	tx, err := e.Pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer func() { _ = tx.Rollback(context.WithoutCancel(ctx)) }()

	rows, err := tx.Query(ctx, `WITH expired AS (
			SELECT id FROM reservations WHERE status = 'PENDING' AND expires_at < now()
			ORDER BY expires_at LIMIT $1 FOR UPDATE SKIP LOCKED),
		released AS (
			UPDATE reservations r SET status = 'RELEASED' FROM expired x WHERE r.id = x.id
			RETURNING r.id, r.order_id, r.sector_id, r.quantity)
		SELECT id::text, order_id::text, sector_id::text, quantity FROM released`, e.BatchSize)
	if err != nil {
		return 0, err
	}
	type released struct {
		id, orderID, sectorID string
		quantity              int
	}
	var batch []released
	for rows.Next() {
		var r released
		if err := rows.Scan(&r.id, &r.orderID, &r.sectorID, &r.quantity); err != nil {
			rows.Close()
			return 0, err
		}
		batch = append(batch, r)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}

	// One UPDATE per sector, always in sector id order: replicas whose batches share sectors then take
	// the row locks in the same order and cannot deadlock (40P01).
	returned := map[string]int{}
	for _, r := range batch {
		returned[r.sectorID] += r.quantity
	}
	sectorIDs := make([]string, 0, len(returned))
	for id := range returned {
		sectorIDs = append(sectorIDs, id)
	}
	slices.Sort(sectorIDs)
	for _, id := range sectorIDs {
		if _, err := tx.Exec(ctx, `UPDATE sectors SET available = available + $1 WHERE id = $2`, returned[id], id); err != nil {
			return 0, err
		}
	}
	for _, r := range batch {
		// The reservation does not keep the saga's correlationId; the order correlates by orderId.
		if err := enqueue(ctx, tx, e.TopicARN, uuid.NewString(), "InventoryReservationExpired", map[string]any{
			"orderId": r.orderID, "reservationId": r.id, "quantity": r.quantity,
		}); err != nil {
			return 0, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, err
	}
	return len(batch), nil
}
