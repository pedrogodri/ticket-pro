// Package ratelimit is the gateway's token bucket in Redis (RFC 0002 §5.5 step 5, §5.8 API4/API6).
package ratelimit

import (
	"context"
	"math"
	"time"

	"github.com/redis/go-redis/v9"
)

// Rule is one bucket: RPS tokens per second refill up to Burst.
type Rule struct {
	RPS   float64
	Burst int
}

// Decision is the result of Allow. RetryAfter is set when Allowed is false.
type Decision struct {
	Allowed    bool
	RetryAfter time.Duration
}

// Limiter checks buckets stored as ratelimit:{key}. Clock is injectable for tests.
type Limiter struct {
	Client redis.UniversalClient
	Clock  func() time.Time
}

// bucket refills by elapsed time and takes one token atomically. The caller's clock is passed in
// (ARGV[3]) so every gateway replica and the tests agree on time without reading Redis TIME.
// Returns {allowed, retry_after_ms}.
var bucket = redis.NewScript(`
local rate = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])
local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1]) or burst
local ts = tonumber(state[2]) or now
now = math.max(now, ts) -- a lagging replica clock must not rewind ts (refill twice)
tokens = math.min(burst, tokens + math.max(0, now - ts) * rate / 1000)
local allowed = 0
local retry = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry = math.ceil((1 - tokens) * 1000 / rate)
end
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', now)
redis.call('PEXPIRE', KEYS[1], ttl)
return {allowed, retry}
`)

// Allow takes one token from key's bucket. An error means Redis was unreachable (caller fails open).
func (l Limiter) Allow(ctx context.Context, key string, rule Rule) (Decision, error) {
	now := l.Clock().UnixMilli()
	// A full bucket needs Burst/RPS seconds to refill; after that the key carries no information.
	ttlMs := int64(math.Ceil(float64(rule.Burst)/rule.RPS*1000)) + 1000
	res, err := bucket.Run(ctx, l.Client, []string{"ratelimit:" + key}, rule.RPS, rule.Burst, now, ttlMs).Int64Slice()
	if err != nil {
		return Decision{}, err
	}
	return Decision{Allowed: res[0] == 1, RetryAfter: time.Duration(res[1]) * time.Millisecond}, nil
}
