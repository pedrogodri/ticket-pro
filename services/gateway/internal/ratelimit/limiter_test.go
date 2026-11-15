package ratelimit

import (
	"context"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func newLimiter(t *testing.T, clock *time.Time) (Limiter, *miniredis.Miniredis) {
	t.Helper()
	mr := miniredis.RunT(t)
	return Limiter{Client: redis.NewClient(&redis.Options{Addr: mr.Addr()}), Clock: func() time.Time { return *clock }}, mr
}

func TestBurstThenDeniedWithRetryAfter(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	l, _ := newLimiter(t, &now)
	rule := Rule{RPS: 1, Burst: 3}
	for i := range 3 {
		if d, err := l.Allow(context.Background(), "user:a", rule); err != nil || !d.Allowed {
			t.Fatalf("request %d: %+v %v", i+1, d, err)
		}
	}
	d, err := l.Allow(context.Background(), "user:a", rule)
	if err != nil || d.Allowed || d.RetryAfter <= 0 || d.RetryAfter > time.Second {
		t.Fatalf("4th request: %+v %v", d, err)
	}
}

func TestTokensRefillOverTime(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	l, _ := newLimiter(t, &now)
	rule := Rule{RPS: 2, Burst: 1}
	ctx := context.Background()
	_, _ = l.Allow(ctx, "user:a", rule)
	if d, _ := l.Allow(ctx, "user:a", rule); d.Allowed {
		t.Fatal("second request should be denied")
	}
	now = now.Add(500 * time.Millisecond)
	if d, err := l.Allow(ctx, "user:a", rule); err != nil || !d.Allowed {
		t.Fatalf("after refill: %+v %v", d, err)
	}
}

func TestKeysAreIndependent(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	l, _ := newLimiter(t, &now)
	rule := Rule{RPS: 1, Burst: 1}
	ctx := context.Background()
	_, _ = l.Allow(ctx, "user:a", rule)
	if d, err := l.Allow(ctx, "user:b", rule); err != nil || !d.Allowed {
		t.Fatalf("user:b: %+v %v", d, err)
	}
}

func TestBucketExpires(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	l, mr := newLimiter(t, &now)
	_, _ = l.Allow(context.Background(), "user:a", Rule{RPS: 1, Burst: 5})
	if ttl := mr.TTL("ratelimit:user:a"); ttl <= 0 || ttl > 6*time.Second {
		t.Fatalf("ttl %v", ttl)
	}
}

func TestRedisDownReturnsError(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	l, mr := newLimiter(t, &now)
	mr.Close()
	if _, err := l.Allow(context.Background(), "user:a", Rule{RPS: 1, Burst: 1}); err == nil {
		t.Fatal("want error")
	}
}
