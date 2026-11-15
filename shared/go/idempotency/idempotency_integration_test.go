//go:build integration

package idempotency_test

import (
	"context"
	"testing"

	"github.com/ticketpro/platform/idempotency"
	"github.com/ticketpro/platform/internal/testenv"
)

func TestP07_RecordIsFreshOncePerConsumerAndMessage(t *testing.T) {
	pool := testenv.Postgres(t)
	ctx := context.Background()
	const m1 = "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55"

	record := func(consumer string) bool {
		tx, err := pool.Begin(ctx)
		if err != nil {
			t.Fatal(err)
		}
		fresh, err := idempotency.Record(ctx, tx, consumer, m1)
		if err != nil {
			t.Fatal(err)
		}
		if err := tx.Commit(ctx); err != nil {
			t.Fatal(err)
		}
		return fresh
	}

	if !record("c1") {
		t.Fatal("(c1,m1) first time must be fresh")
	}
	if record("c1") {
		t.Fatal("(c1,m1) second time must not be fresh")
	}
	if !record("c2") {
		t.Fatal("(c2,m1) must be fresh for another consumer")
	}
}
