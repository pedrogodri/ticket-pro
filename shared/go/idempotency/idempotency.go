// Package idempotency records processed messages in the caller's transaction (ADR-0006).
package idempotency

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5"
)

// Record inserts (consumer, messageID) into processed_messages inside tx. fresh is false when the
// pair was already recorded: the message is a duplicate and the caller must roll back and ack.
func Record(ctx context.Context, tx pgx.Tx, consumer, messageID string) (fresh bool, err error) {
	tag, err := tx.Exec(ctx,
		`INSERT INTO processed_messages (consumer, message_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`,
		consumer, messageID)
	if err != nil {
		return false, fmt.Errorf("record processed message: %w", err)
	}
	return tag.RowsAffected() == 1, nil
}
