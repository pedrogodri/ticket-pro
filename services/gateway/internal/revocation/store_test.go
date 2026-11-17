package revocation

import (
	"context"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func newStore(t *testing.T) (Store, *miniredis.Miniredis) {
	t.Helper()
	mr := miniredis.RunT(t)
	return Store{Client: redis.NewClient(&redis.Options{Addr: mr.Addr()})}, mr
}

func TestRevokeSetsKeyWithTTLUntilExpiry(t *testing.T) {
	s, mr := newStore(t)
	now := time.Date(2026, 11, 20, 18, 30, 0, 0, time.UTC)
	if err := s.Revoke(context.Background(), "j-1", now.Add(10*time.Minute), now); err != nil {
		t.Fatal(err)
	}
	if !mr.Exists("revoked:j-1") {
		t.Fatal("key revoked:j-1 not set")
	}
	if ttl := mr.TTL("revoked:j-1"); ttl != 10*time.Minute {
		t.Fatalf("ttl %v, want 10m", ttl)
	}
}

func TestRevokeIgnoresExpiredToken(t *testing.T) {
	s, mr := newStore(t)
	now := time.Now()
	if err := s.Revoke(context.Background(), "j-old", now.Add(-time.Second), now); err != nil {
		t.Fatal(err)
	}
	if mr.Exists("revoked:j-old") {
		t.Fatal("expired token must not be stored")
	}
}

func TestIsRevoked(t *testing.T) {
	s, _ := newStore(t)
	ctx := context.Background()
	now := time.Now()
	_ = s.Revoke(ctx, "j-1", now.Add(time.Minute), now)
	if got, err := s.IsRevoked(ctx, "j-1"); err != nil || !got {
		t.Fatalf("j-1 revoked=%v err=%v", got, err)
	}
	if got, err := s.IsRevoked(ctx, "j-2"); err != nil || got {
		t.Fatalf("j-2 revoked=%v err=%v", got, err)
	}
}

func TestIsRevokedReportsUnavailableRedis(t *testing.T) {
	s, mr := newStore(t)
	mr.Close()
	if _, err := s.IsRevoked(context.Background(), "j-1"); err == nil {
		t.Fatal("want error when Redis is down")
	}
}
