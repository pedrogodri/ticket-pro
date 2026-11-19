// Package proxy is the gateway's edge: routing by first path segment, JWT validation and identity headers
// (RFC 0002 §5.5).
package proxy

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"

	"github.com/ticketpro/gateway/internal/jwtauth"
	"github.com/ticketpro/gateway/internal/problem"
	"github.com/ticketpro/gateway/internal/ratelimit"
)

// TokenVerifier validates a bearer token.
type TokenVerifier interface {
	Verify(ctx context.Context, token string) (jwtauth.Claims, error)
}

// RevocationChecker reports whether a token id was revoked (ADR-0014).
type RevocationChecker interface {
	IsRevoked(ctx context.Context, jti string) (bool, error)
}

// RateLimiter takes one token from a bucket (internal/ratelimit).
type RateLimiter interface {
	Allow(ctx context.Context, key string, rule ratelimit.Rule) (ratelimit.Decision, error)
}

// Limits are the buckets per route class (RFC 0002 §5.8 API4/API6).
type Limits struct {
	Default ratelimit.Rule // authenticated, per user (RATE_LIMIT_*)
	Public  ratelimit.Rule // public routes, per IP (RATE_LIMIT_PUBLIC_*)
	Login   ratelimit.Rule // POST /auth/login, per IP, against brute force (AUTH_LOGIN_*)
	Orders  ratelimit.Rule // POST /orders, per user, against scalping (ORDERS_*)
}

// PublicRoutes lists the only routes served without an access token (RFC 0002
// §5.5). Refresh is public because it authenticates with the refresh token.
func PublicRoutes() map[string]bool {
	return map[string]bool{
		"POST /auth/register": true,
		"POST /auth/login":    true,
		"POST /auth/refresh":  true,
	}
}

// Config configures the edge handler.
type Config struct {
	Routes          map[string]*url.URL // first path segment → upstream (GATEWAY_ROUTES)
	Public          map[string]bool     // "METHOD /path" that need no token
	MaxBodyBytes    int64               // GATEWAY_MAX_BODY_BYTES
	UpstreamTimeout time.Duration       // GATEWAY_UPSTREAM_TIMEOUT_MS
	Verifier        TokenVerifier
	Revocations     RevocationChecker // nil: no revocation check
	Limiter         RateLimiter       // nil: no rate limit
	Limits          Limits
	// RedisCooldown (REDIS_COOLDOWN_MS): after a Redis failure, revocation and rate limit checks are
	// skipped (fail-open) for this long instead of paying the timeout on every request. 0 = always try.
	RedisCooldown time.Duration
	Now           func() time.Time // injectable clock; nil = time.Now
	Logger        *slog.Logger
}

type ctxKey struct{}

// New returns the edge handler.
func New(cfg Config) http.Handler {
	proxies := make(map[string]*httputil.ReverseProxy, len(cfg.Routes))
	for name, target := range cfg.Routes {
		rp := httputil.NewSingleHostReverseProxy(target)
		// otelhttp opens a client span per upstream call and injects its traceparent (ADR-0012), replacing
		// any traceparent the client sent.
		rp.Transport = otelhttp.NewTransport(&http.Transport{ResponseHeaderTimeout: cfg.UpstreamTimeout, Proxy: nil,
			DialContext: (&net.Dialer{Timeout: cfg.UpstreamTimeout}).DialContext})
		rp.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
			correlationID, _ := r.Context().Value(ctxKey{}).(string)
			var netErr net.Error
			if errors.As(err, &netErr) && netErr.Timeout() {
				cfg.Logger.Warn("upstream_timeout", "path", r.URL.Path, "correlationId", correlationID)
				problem.Write(w, r, http.StatusGatewayTimeout, "UPSTREAM_TIMEOUT", correlationID)
				return
			}
			cfg.Logger.Warn("upstream_unavailable", "path", r.URL.Path, "error", err.Error(), "correlationId", correlationID)
			problem.Write(w, r, http.StatusBadGateway, "BAD_GATEWAY", correlationID)
		}
		proxies[name] = rp
	}

	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	guard := &redisGuard{cooldown: cfg.RedisCooldown, now: cfg.Now, logger: cfg.Logger}
	allow := func(w http.ResponseWriter, r *http.Request, key string, rule ratelimit.Rule, correlationID string) bool {
		return allowRequest(w, r, cfg, guard, key, rule, correlationID)
	}

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlationID := uuid.NewString()
		if client := r.Header.Get("X-Correlation-Id"); client != "" {
			r.Header.Set("X-Client-Request-Id", client)
		}
		r.Header.Set("X-Correlation-Id", correlationID)
		stripIdentity(r.Header)
		r = r.WithContext(context.WithValue(r.Context(), ctxKey{}, correlationID))

		segment := strings.SplitN(strings.TrimPrefix(r.URL.Path, "/"), "/", 2)[0]
		rp, ok := proxies[segment]
		if !ok {
			problem.Write(w, r, http.StatusNotFound, "NOT_FOUND", correlationID)
			return
		}
		if r.ContentLength > cfg.MaxBodyBytes {
			problem.Write(w, r, http.StatusRequestEntityTooLarge, "PAYLOAD_TOO_LARGE", correlationID)
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, cfg.MaxBodyBytes)

		route := r.Method + " " + r.URL.Path
		if cfg.Public[route] {
			key, rule := "ip:"+clientIP(r), cfg.Limits.Public
			if route == "POST /auth/login" {
				key, rule = "login:"+clientIP(r), cfg.Limits.Login
			}
			if !allow(w, r, key, rule, correlationID) {
				return
			}
		} else {
			token, found := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
			claims, err := cfg.Verifier.Verify(r.Context(), strings.TrimSpace(token))
			if !found || err != nil {
				reason := "missing bearer token"
				if err != nil {
					reason = err.Error()
				}
				cfg.Logger.Info("token_rejected", "reason", reason, "path", r.URL.Path, "correlationId", correlationID)
				// Invalid tokens cost a signature check each: throttle them per IP (valid ones are not counted).
				if !allow(w, r, "authfail:"+clientIP(r), cfg.Limits.Public, correlationID) {
					return
				}
				w.Header().Set("WWW-Authenticate", `Bearer error="invalid_token"`)
				problem.Write(w, r, http.StatusUnauthorized, "UNAUTHENTICATED", correlationID)
				return
			}
			if cfg.Revocations != nil && guard.available() {
				revoked, err := cfg.Revocations.IsRevoked(r.Context(), claims.ID)
				if err != nil {
					// Fail-open (ADR-0014): the exposure is bounded by the 15 min access token TTL.
					cfg.Logger.Warn("revocation_check_unavailable", "error", err.Error(), "correlationId", correlationID)
					guard.failed()
				} else if revoked {
					cfg.Logger.Info("token_rejected", "reason", "token revoked", "path", r.URL.Path, "correlationId", correlationID)
					w.Header().Set("WWW-Authenticate", `Bearer error="invalid_token"`)
					problem.Write(w, r, http.StatusUnauthorized, "UNAUTHENTICATED", correlationID)
					return
				}
			}
			key, rule := "user:"+claims.Subject, cfg.Limits.Default
			if route == "POST /orders" {
				key, rule = "orders:"+claims.Subject, cfg.Limits.Orders
			}
			if !allow(w, r, key, rule, correlationID) {
				return
			}
			r.Header.Set("X-User-Id", claims.Subject)
			r.Header.Set("X-User-Email", claims.Email)
			w.Header().Set("Cache-Control", "no-store")
			w.Header().Set("X-Content-Type-Options", "nosniff")
		}
		rp.ServeHTTP(w, r)
	})
}

// allow applies the bucket; false means a 429 was written. Redis errors fail open (ADR-0014).
func allowRequest(w http.ResponseWriter, r *http.Request, cfg Config, guard *redisGuard, key string, rule ratelimit.Rule, correlationID string) bool {
	if cfg.Limiter == nil || !guard.available() {
		return true
	}
	d, err := cfg.Limiter.Allow(r.Context(), key, rule)
	if err != nil {
		cfg.Logger.Warn("rate_limit_unavailable", "error", err.Error(), "correlationId", correlationID)
		guard.failed()
		return true
	}
	if d.Allowed {
		return true
	}
	seconds := max(1, int((d.RetryAfter+time.Second-1)/time.Second))
	cfg.Logger.Info("rate_limited", "key", key, "path", r.URL.Path, "correlationId", correlationID)
	w.Header().Set("Retry-After", strconv.Itoa(seconds))
	problem.Write(w, r, http.StatusTooManyRequests, "RATE_LIMITED", correlationID)
	return false
}

// redisGuard suspends the Redis checks for cooldown after a failure, so an outage costs one timeout
// and one log line per cooldown instead of two of each per request.
type redisGuard struct {
	cooldown  time.Duration
	now       func() time.Time
	logger    *slog.Logger
	skipUntil atomic.Int64 // unix nanos
}

func (g *redisGuard) available() bool { return g.now().UnixNano() >= g.skipUntil.Load() }

func (g *redisGuard) failed() {
	if g.cooldown <= 0 {
		return
	}
	until := g.now().Add(g.cooldown)
	if prev := g.skipUntil.Swap(until.UnixNano()); g.now().UnixNano() >= prev {
		g.logger.Warn("redis_checks_suspended", "until", until.UTC().Format(time.RFC3339))
	}
}

// clientIP is the TCP peer. X-Forwarded-For is ignored: the gateway is the edge, so any value
// in it came from the client and would let it pick its own bucket.
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// stripIdentity removes identity headers a client could forge (RFC 0002 §5.5 step 2).
func stripIdentity(h http.Header) {
	h.Del("X-User-Id")
	h.Del("X-User-Email")
	for name := range h {
		if strings.HasPrefix(http.CanonicalHeaderKey(name), "X-Internal-") {
			h.Del(name)
		}
	}
}
