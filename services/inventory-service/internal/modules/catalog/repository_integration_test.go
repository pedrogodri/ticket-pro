//go:build integration

package catalog

import (
	"context"
	"testing"

	"github.com/ticketpro/inventory-service/internal/testdb"
)

func TestSeedHasTwoEventsAndTheF5Sector(t *testing.T) {
	repo := NewRepository(testdb.Pool(t))
	ctx := context.Background()
	var all []Event
	var cursor *Cursor
	for {
		page, err := repo.ListEvents(ctx, 1, cursor)
		if err != nil {
			t.Fatal(err)
		}
		if len(page) == 0 {
			break
		}
		all = append(all, page...)
		c := CursorOf(page[len(page)-1])
		cursor = &c
	}
	if len(all) != 2 {
		t.Fatalf("want 2 seeded events paging one by one, got %d", len(all))
	}
	found := false
	for _, e := range all {
		d, ok, err := repo.GetEvent(ctx, e.ID)
		if err != nil || !ok {
			t.Fatalf("event %s: %v %v", e.ID, ok, err)
		}
		for _, s := range d.Sectors {
			if s.Name == "F5" {
				found = s.Available == 10 && s.Capacity == 10 && s.UnitPriceCents == 15000 && s.Currency == "BRL"
			}
		}
	}
	if !found {
		t.Fatal("seed must contain sector F5 with 10 units at 15000 BRL")
	}
}
