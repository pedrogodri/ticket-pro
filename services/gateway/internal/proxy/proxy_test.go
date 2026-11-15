package proxy

import (
	"context"
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
