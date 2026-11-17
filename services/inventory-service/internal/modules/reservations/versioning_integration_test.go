//go:build integration

package reservations_test

import (
	"context"
	"encoding/json"
	"slices"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ticketpro/inventory-service/internal/platform/envelope"
)

// handleVersion runs the OrderCreated handler with an envelope of the given version.
func handleVersion(t *testing.T, pool *pgxpool.Pool, version int, payload map[string]any) error {
	t.Helper()
	created, _, _ := handlers()
	env, err := envelope.New(uuid.NewString(), "OrderCreated", version, payload)
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(env)
	if err := validator.Validate(raw); err != nil {
		return err
	}
	ctx := context.Background()
	tx, err := pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := created.Handle(ctx, tx, env); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func channelOf(t *testing.T, pool *pgxpool.Pool, order string) string {
	t.Helper()
	var c string
	if err := pool.QueryRow(context.Background(), `SELECT channel FROM reservations WHERE order_id = $1`, order).Scan(&c); err != nil {
		t.Fatal(err)
	}
	return c
}

func TestOrderCreated_SupportsV1AndV2(t *testing.T) {
	created, _, _ := handlers()
	if !slices.Equal(created.Versions(), []int{1, 2}) {
		t.Fatalf("versions %v", created.Versions())
	}
}

func TestOrderCreated_V1IsUpcastToChannelAPI(t *testing.T) {
	pool := setup(t)
	sector, order := newSector(t, pool, 5), uuid.NewString()
	if err := handleVersion(t, pool, 1, orderCreated(order, sector, 2)); err != nil {
		t.Fatal(err)
	}
	if c := channelOf(t, pool, order); c != "API" {
		t.Fatalf("channel %s, want API", c)
	}
	if n := available(t, pool, sector); n != 3 {
		t.Fatalf("available %d, want 3", n)
	}
}

func TestOrderCreated_V2KeepsItsChannel(t *testing.T) {
	pool := setup(t)
	sector, order := newSector(t, pool, 5), uuid.NewString()
	p := orderCreated(order, sector, 1)
	p["channel"] = "WEB"
	if err := handleVersion(t, pool, 2, p); err != nil {
		t.Fatal(err)
	}
	if c := channelOf(t, pool, order); c != "WEB" {
		t.Fatalf("channel %s, want WEB", c)
	}
}

func TestOrderCreated_V2WithoutValidChannelBreaksTheContract(t *testing.T) {
	pool := setup(t)
	sector := newSector(t, pool, 5)
	for _, channel := range []any{nil, "FAX"} {
		p := orderCreated(uuid.NewString(), sector, 1)
		if channel != nil {
			p["channel"] = channel
		}
		if err := handleVersion(t, pool, 2, p); err == nil {
			t.Errorf("channel %v: want contract violation", channel)
		}
	}
}
