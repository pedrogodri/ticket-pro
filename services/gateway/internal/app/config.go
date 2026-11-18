// Package app wires configuration, HTTP server and lifecycle of the gateway.
package app

import (
	"fmt"
	"log/slog"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Config is the validated runtime configuration read from environment variables.
type Config struct {
	ServiceName       string
	Port              int
	LogLevel          slog.Level
	Routes            map[string]*url.URL // GATEWAY_ROUTES: segment=url,...
	JWKSURL           string
	JWTIssuer         string
	JWTAudience       string
	JWKSCacheTTL      time.Duration
	ClockSkew         time.Duration
	MaxBodyBytes      int64
	UpstreamTimeout   time.Duration
	RedisURL          string        // REDIS_URL: the gateway's own Redis (revocation + rate limit)
	RedisTimeout      time.Duration // REDIS_TIMEOUT_MS: per command; past it the check fails open
	RedisCooldown     time.Duration // REDIS_COOLDOWN_MS: checks skipped for this long after a Redis failure
	QueueURL          string        // QUEUE_URL: ticketpro-gateway (TokenRevoked)
	ContractsDir      string        // CONTRACTS_DIR: vendored contracts for envelope validation
	SQSWaitTimeS      int           // SQS_WAIT_TIME_S: long poll
	HTTPClientTimeout time.Duration // HTTP_CLIENT_TIMEOUT_MS: AWS SDK calls
	Limits            Limits
}

// Rule is one token bucket: RPS refill up to Burst.
type Rule struct {
	RPS   float64
	Burst int
}

// Limits are the rate limit buckets per route class (RFC 0002 §5.8).
type Limits struct{ Default, Public, Login, Orders Rule }

// LoadConfig reads and validates configuration. The returned error names the offending variable.
func LoadConfig(getenv func(string) string) (Config, error) {
	r := reader{getenv: getenv}
	cfg := Config{
		ServiceName:       r.required("SERVICE_NAME"),
		Port:              r.port("PORT"),
		LogLevel:          r.level("LOG_LEVEL"),
		Routes:            r.routes("GATEWAY_ROUTES"),
		JWKSURL:           r.required("JWKS_URL"),
		JWTIssuer:         r.optional("JWT_ISSUER", "ticketpro-auth"),
		JWTAudience:       r.optional("JWT_AUDIENCE", "ticketpro-api"),
		JWKSCacheTTL:      time.Duration(r.positiveInt("JWKS_CACHE_TTL_S", 300)) * time.Second,
		ClockSkew:         time.Duration(r.positiveInt("JWT_CLOCK_SKEW_S", 30)) * time.Second,
		MaxBodyBytes:      int64(r.positiveInt("GATEWAY_MAX_BODY_BYTES", 65536)),
		UpstreamTimeout:   r.millis("GATEWAY_UPSTREAM_TIMEOUT_MS", 5000),
		RedisURL:          r.redisURL("REDIS_URL"),
		RedisTimeout:      r.millis("REDIS_TIMEOUT_MS", 200),
		RedisCooldown:     r.millis("REDIS_COOLDOWN_MS", 5000),
		QueueURL:          r.required("QUEUE_URL"),
		ContractsDir:      r.required("CONTRACTS_DIR"),
		SQSWaitTimeS:      r.positiveInt("SQS_WAIT_TIME_S", 10),
		HTTPClientTimeout: r.millis("HTTP_CLIENT_TIMEOUT_MS", 3000),
		Limits: Limits{
			Default: Rule{r.positiveFloat("RATE_LIMIT_RPS", 10), r.positiveInt("RATE_LIMIT_BURST", 20)},
			Public:  Rule{r.positiveFloat("RATE_LIMIT_PUBLIC_RPS", 5), r.positiveInt("RATE_LIMIT_PUBLIC_BURST", 10)},
			Login:   Rule{r.positiveFloat("AUTH_LOGIN_RPS", 1), r.positiveInt("AUTH_LOGIN_BURST", 5)},
			Orders:  Rule{r.positiveFloat("ORDERS_RPS", 2), r.positiveInt("ORDERS_BURST", 5)},
		},
	}
	if r.err != nil {
		return Config{}, r.err
	}
	return cfg, nil
}

func (r *reader) optional(name, fallback string) string {
	if v := strings.TrimSpace(r.getenv(name)); v != "" {
		return v
	}
	return fallback
}

// routes parses "segment=http://host:port,..." (first path segment → upstream).
func (r *reader) routes(name string) map[string]*url.URL {
	raw := r.required(name)
	routes := map[string]*url.URL{}
	if raw == "" {
		return routes
	}
	for _, entry := range strings.Split(raw, ",") {
		segment, target, ok := strings.Cut(strings.TrimSpace(entry), "=")
		u, err := url.Parse(target)
		if !ok || segment == "" || err != nil || u.Scheme == "" || u.Host == "" {
			r.fail("%s must be segment=http://host:port,...; got %q", name, entry)
			return routes
		}
		routes[segment] = u
	}
	return routes
}

func (r *reader) redisURL(name string) string {
	raw := r.required(name)
	if raw == "" {
		return ""
	}
	if u, err := url.Parse(raw); err != nil || (u.Scheme != "redis" && u.Scheme != "rediss") || u.Host == "" {
		r.fail("%s must be redis://host:port/db; got %q", name, raw)
	}
	return raw
}

// reader keeps the first validation error so LoadConfig reads like a declaration.
type reader struct {
	getenv func(string) string
	err    error
}

func (r *reader) fail(format string, args ...any) {
	if r.err == nil {
		r.err = fmt.Errorf(format, args...)
	}
}

func (r *reader) required(name string) string {
	v := strings.TrimSpace(r.getenv(name))
	if v == "" {
		r.fail("%s is required", name)
	}
	return v
}

func (r *reader) port(name string) int {
	raw := r.required(name)
	if raw == "" {
		return 0
	}
	port, err := strconv.Atoi(raw)
	if err != nil || port < 1 || port > 65535 {
		r.fail("%s must be an integer between 1 and 65535, got %q", name, raw)
	}
	return port
}

func (r *reader) level(name string) slog.Level {
	raw := r.required(name)
	var level slog.Level
	switch strings.ToLower(raw) {
	case "":
	case "debug", "info", "warn", "error":
		_ = level.UnmarshalText([]byte(raw))
	default:
		r.fail("%s must be one of debug, info, warn, error; got %q", name, raw)
	}
	return level
}

func (r *reader) positiveInt(name string, def int) int {
	raw := strings.TrimSpace(r.getenv(name))
	if raw == "" {
		return def
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n < 1 {
		r.fail("%s must be a positive integer, got %q", name, raw)
	}
	return n
}

func (r *reader) positiveFloat(name string, def float64) float64 {
	raw := strings.TrimSpace(r.getenv(name))
	if raw == "" {
		return def
	}
	f, err := strconv.ParseFloat(raw, 64)
	if err != nil || f <= 0 {
		r.fail("%s must be a positive number, got %q", name, raw)
	}
	return f
}

func (r *reader) millis(name string, def int) time.Duration {
	return time.Duration(r.positiveInt(name, def)) * time.Millisecond
}
