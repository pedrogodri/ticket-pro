// Package revocation keeps the gateway's copy of the jti blacklist (ADR-0014): auth publishes
// TokenRevoked, the gateway stores revoked:{jti} in its own Redis until the token would expire.
package revocation

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
)

const keyPrefix = "revoked:"

// Store reads and writes revoked:{jti} keys.
type Store struct {
	Client redis.UniversalClient
}

// Revoke stores jti until expiresAt. A token that already expired is ignored: nothing can use it.
func (s Store) Revoke(ctx context.Context, jti string, expiresAt, now time.Time) error {
	ttl := expiresAt.Sub(now)
	if ttl <= 0 {
		return nil
	}
	return s.Client.Set(ctx, keyPrefix+jti, 1, ttl).Err()
}

// IsRevoked reports whether jti is on the blacklist. The caller decides what an error means (fail-open).
func (s Store) IsRevoked(ctx context.Context, jti string) (bool, error) {
	n, err := s.Client.Exists(ctx, keyPrefix+jti).Result()
	return n > 0, err
}
