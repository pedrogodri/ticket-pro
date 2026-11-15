package catalog

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Repository reads the catalog.
type Repository interface {
	ListEvents(ctx context.Context, limit int, after *Cursor) ([]Event, error)
	GetEvent(ctx context.Context, id string) (EventDetail, bool, error)
}

type pgRepository struct{ pool *pgxpool.Pool }

// NewRepository returns the Postgres catalog repository.
func NewRepository(pool *pgxpool.Pool) Repository { return pgRepository{pool: pool} }

func (r pgRepository) ListEvents(ctx context.Context, limit int, after *Cursor) ([]Event, error) {
	query := `SELECT id::text, name, starts_at, venue FROM events ORDER BY starts_at, id LIMIT $1`
	args := []any{limit}
	if after != nil {
		query = `SELECT id::text, name, starts_at, venue FROM events
		         WHERE (starts_at, id) > ($2, $3::uuid) ORDER BY starts_at, id LIMIT $1`
		args = append(args, after.StartsAt, after.ID)
	}
	rows, err := r.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (Event, error) {
		var e Event
		err := row.Scan(&e.ID, &e.Name, &e.StartsAt, &e.Venue)
		return e, err
	})
}

func (r pgRepository) GetEvent(ctx context.Context, id string) (EventDetail, bool, error) {
	var d EventDetail
	err := r.pool.QueryRow(ctx, `SELECT id::text, name, starts_at, venue FROM events WHERE id = $1`, id).
		Scan(&d.ID, &d.Name, &d.StartsAt, &d.Venue)
	if errors.Is(err, pgx.ErrNoRows) {
		return EventDetail{}, false, nil
	}
	if err != nil {
		return EventDetail{}, false, err
	}
	rows, err := r.pool.Query(ctx, `SELECT id::text, name, capacity, available, unit_price_cents, currency
		FROM sectors WHERE event_id = $1 ORDER BY unit_price_cents DESC, name`, id)
	if err != nil {
		return EventDetail{}, false, err
	}
	d.Sectors, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (Sector, error) {
		var s Sector
		err := row.Scan(&s.ID, &s.Name, &s.Capacity, &s.Available, &s.UnitPriceCents, &s.Currency)
		return s, err
	})
	return d, err == nil, err
}
