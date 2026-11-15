package proxy

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/ticketpro/gateway/internal/jwtauth"
	"github.com/ticketpro/gateway/internal/ratelimit"
)

type fakeVerifier struct{}

func (fakeVerifier) Verify(_ context.Context, token string) (jwtauth.Claims, error) {
	if token != "good" {
		return jwtauth.Claims{}, jwtauth.ErrInvalidToken
	}
	return jwtauth.Claims{Subject: "user-1", Email: "ana@exemplo.com", ID: "j-1"}, nil
}

type seen struct {
	headers http.Header
	path    string
	called  bool
}

func upstream(t *testing.T, s *seen, delay time.Duration) *url.URL {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.called, s.headers, s.path = true, r.Header.Clone(), r.URL.RequestURI()
		time.Sleep(delay)
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	t.Cleanup(srv.Close)
	u, _ := url.Parse(srv.URL)
	return u
}

func handler(t *testing.T, s *seen, delay time.Duration) http.Handler {
	u := upstream(t, s, delay)
	return New(Config{
		Routes:          map[string]*url.URL{"auth": u, "orders": u, "events": u, "me": u},
		Public:          map[string]bool{"POST /auth/register": true, "POST /auth/login": true},
		MaxBodyBytes:    65536,
		UpstreamTimeout: 500 * time.Millisecond,
		Verifier:        fakeVerifier{},
		Logger:          slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
}

func do(h http.Handler, method, path, token string, body string, headers map[string]string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

var uuidRe = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func TestValidTokenIsForwardedWithIdentity(t *testing.T) {
	var s seen
	rec := do(handler(t, &s, 0), "GET", "/orders/42?x=1", "good", "", map[string]string{"X-Correlation-Id": "client-id"})
	if rec.Code != 200 || s.path != "/orders/42?x=1" {
		t.Fatalf("status %d path %q", rec.Code, s.path)
	}
	if s.headers.Get("X-User-Id") != "user-1" || s.headers.Get("X-User-Email") != "ana@exemplo.com" {
		t.Fatalf("identity headers %v", s.headers)
	}
	if !uuidRe.MatchString(s.headers.Get("X-Correlation-Id")) || s.headers.Get("X-Client-Request-Id") != "client-id" {
		t.Fatalf("correlation headers %v", s.headers)
	}
	if rec.Header().Get("Cache-Control") != "no-store" || rec.Header().Get("X-Content-Type-Options") != "nosniff" {
		t.Fatalf("security headers %v", rec.Header())
	}
}

func TestInvalidOrMissingTokenIs401AndNotForwarded(t *testing.T) {
	for _, token := range []string{"", "bad"} {
		var s seen
		rec := do(handler(t, &s, 0), "GET", "/orders", token, "", nil)
		if rec.Code != 401 || s.called {
			t.Fatalf("token %q: status %d, upstream called %v", token, rec.Code, s.called)
		}
		if !strings.Contains(rec.Header().Get("WWW-Authenticate"), `error="invalid_token"`) ||
			!strings.HasPrefix(rec.Header().Get("Content-Type"), "application/problem+json") {
			t.Fatalf("headers %v", rec.Header())
		}
	}
}

// Review Focus 2: a client cannot forge identity headers.
func TestClientIdentityHeadersAreStripped(t *testing.T) {
	var s seen
	do(handler(t, &s, 0), "POST", "/auth/register", "", `{}`, map[string]string{
		"X-User-Id": "attacker", "X-User-Email": "x@y", "X-Internal-Role": "admin",
	})
	if !s.called {
		t.Fatal("public route must be forwarded without a token")
	}
	if s.headers.Get("X-User-Id") != "" || s.headers.Get("X-User-Email") != "" || s.headers.Get("X-Internal-Role") != "" {
		t.Fatalf("forged headers reached upstream: %v", s.headers)
	}
}

func TestEdgeProtections(t *testing.T) {
	var s seen
	h := handler(t, &s, 0)
	if rec := do(h, "POST", "/orders", "good", strings.Repeat("a", 65537), nil); rec.Code != 413 {
		t.Fatalf("body over limit: %d", rec.Code)
	}
	for _, path := range []string{"/.well-known/jwks.json", "/unknown", "/health/ready-of-someone"} {
		if rec := do(h, "GET", path, "good", "", nil); rec.Code != 404 {
			t.Fatalf("%s: %d, want 404", path, rec.Code)
		}
	}
	var slow seen
	if rec := do(handler(t, &slow, time.Second), "GET", "/events", "good", "", nil); rec.Code != 504 {
		t.Fatalf("slow upstream: %d, want 504", rec.Code)
	}
}

func TestUpstreamDownIs502(t *testing.T) {
	dead, _ := url.Parse("http://127.0.0.1:1")
	h := New(Config{Routes: map[string]*url.URL{"events": dead}, Public: map[string]bool{}, MaxBodyBytes: 1024,
		UpstreamTimeout: time.Second, Verifier: fakeVerifier{}, Logger: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if rec := do(h, "GET", "/events", "good", "", nil); rec.Code != 502 {
		t.Fatalf("status %d, want 502", rec.Code)
	}
}

type fakeRevocations struct {
	revoked map[string]bool
	err     error
}

func (f fakeRevocations) IsRevoked(_ context.Context, jti string) (bool, error) {
	return f.revoked[jti], f.err
}

func handlerWith(t *testing.T, s *seen, mutate func(*Config)) http.Handler {
	u := upstream(t, s, 0)
	cfg := Config{
		Routes:          map[string]*url.URL{"auth": u, "orders": u, "events": u, "me": u},
		Public:          map[string]bool{"POST /auth/register": true, "POST /auth/login": true},
		MaxBodyBytes:    65536,
		UpstreamTimeout: 500 * time.Millisecond,
		Verifier:        fakeVerifier{},
		Logger:          slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
	mutate(&cfg)
	return New(cfg)
}

func TestRevokedTokenIsRejected(t *testing.T) {
	var s seen
	h := handlerWith(t, &s, func(c *Config) { c.Revocations = fakeRevocations{revoked: map[string]bool{"j-1": true}} })
	rec := do(h, "GET", "/me", "good", "", nil)
	if rec.Code != 401 || !strings.Contains(rec.Body.String(), "UNAUTHENTICATED") || s.called {
		t.Fatalf("status %d body %s forwarded=%v", rec.Code, rec.Body, s.called)
	}
}

func TestRevocationStoreDownFailsOpen(t *testing.T) {
	var s seen
	var logs strings.Builder
	h := handlerWith(t, &s, func(c *Config) {
		c.Revocations = fakeRevocations{err: io.ErrUnexpectedEOF}
		c.Logger = slog.New(slog.NewTextHandler(&logs, nil))
	})
	rec := do(h, "GET", "/me", "good", "", nil)
	if rec.Code != 200 || !s.called || !strings.Contains(logs.String(), "revocation_check_unavailable") {
		t.Fatalf("status %d forwarded=%v logs %s", rec.Code, s.called, logs.String())
	}
}

type fakeLimiter struct {
	deny map[string]bool
	err  error
	keys []string
}

func (f *fakeLimiter) Allow(_ context.Context, key string, rule ratelimit.Rule) (ratelimit.Decision, error) {
	f.keys = append(f.keys, fmt.Sprintf("%s@%g/%d", key, rule.RPS, rule.Burst))
	if f.err != nil {
		return ratelimit.Decision{}, f.err
	}
	if f.deny[key] {
		return ratelimit.Decision{RetryAfter: 1500 * time.Millisecond}, nil
	}
	return ratelimit.Decision{Allowed: true}, nil
}

var testLimits = Limits{
	Default: ratelimit.Rule{RPS: 10, Burst: 20}, Public: ratelimit.Rule{RPS: 5, Burst: 10},
	Login: ratelimit.Rule{RPS: 1, Burst: 5}, Orders: ratelimit.Rule{RPS: 2, Burst: 5},
}

func withLimiter(l *fakeLimiter) func(*Config) {
	return func(c *Config) { c.Limiter, c.Limits = l, testLimits }
}

func doFrom(h http.Handler, method, path, token, remote string, headers map[string]string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, nil)
	req.RemoteAddr = remote
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestRateLimitedRequestGets429WithRetryAfter(t *testing.T) {
	var s seen
	h := handlerWith(t, &s, withLimiter(&fakeLimiter{deny: map[string]bool{"user:user-1": true}}))
	rec := doFrom(h, "GET", "/me", "good", "10.0.0.1:5000", nil)
	if rec.Code != 429 || rec.Header().Get("Retry-After") != "2" || !strings.Contains(rec.Body.String(), "RATE_LIMITED") || !strings.Contains(rec.Body.String(), "Muitas requisi") || s.called {
		t.Fatalf("status %d retry %q body %s forwarded=%v", rec.Code, rec.Header().Get("Retry-After"), rec.Body, s.called)
	}
}

func TestRateLimitKeysPerRoute(t *testing.T) {
	cases := []struct{ method, path, token, want string }{
		{"POST", "/auth/register", "", "ip:10.0.0.1@5/10"},
		{"POST", "/auth/login", "", "login:10.0.0.1@1/5"},
		{"POST", "/orders", "good", "orders:user-1@2/5"},
		{"GET", "/orders/42", "good", "user:user-1@10/20"},
	}
	for _, c := range cases {
		var s seen
		l := &fakeLimiter{}
		doFrom(handlerWith(t, &s, withLimiter(l)), c.method, c.path, c.token, "10.0.0.1:5000", nil)
		if len(l.keys) != 1 || l.keys[0] != c.want {
			t.Errorf("%s %s: keys %v, want [%s]", c.method, c.path, l.keys, c.want)
		}
	}
}

func TestForwardedForDoesNotChangeTheIPKey(t *testing.T) {
	var s seen
	l := &fakeLimiter{}
	doFrom(handlerWith(t, &s, withLimiter(l)), "POST", "/auth/login", "", "10.0.0.1:5000", map[string]string{"X-Forwarded-For": "1.2.3.4"})
	if len(l.keys) != 1 || l.keys[0] != "login:10.0.0.1@1/5" {
		t.Fatalf("keys %v", l.keys)
	}
}

func TestRateLimiterDownFailsOpen(t *testing.T) {
	var s seen
	var logs strings.Builder
	h := handlerWith(t, &s, func(c *Config) {
		withLimiter(&fakeLimiter{err: io.ErrUnexpectedEOF})(c)
		c.Logger = slog.New(slog.NewTextHandler(&logs, nil))
	})
	rec := doFrom(h, "GET", "/me", "good", "10.0.0.1:5000", nil)
	if rec.Code != 200 || !s.called || !strings.Contains(logs.String(), "rate_limit_unavailable") {
		t.Fatalf("status %d forwarded=%v logs %s", rec.Code, s.called, logs.String())
	}
}
