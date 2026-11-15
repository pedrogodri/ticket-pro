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
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/ticketpro/gateway/internal/jwtauth"
	"github.com/ticketpro/gateway/internal/problem"
)

// TokenVerifier validates a bearer token.
type TokenVerifier interface {
	Verify(ctx context.Context, token string) (jwtauth.Claims, error)
}

// Config configures the edge handler.
type Config struct {
	Routes          map[string]*url.URL // first path segment → upstream (GATEWAY_ROUTES)
	Public          map[string]bool     // "METHOD /path" that need no token
	MaxBodyBytes    int64               // GATEWAY_MAX_BODY_BYTES
	UpstreamTimeout time.Duration       // GATEWAY_UPSTREAM_TIMEOUT_MS
	Verifier        TokenVerifier
	Logger          *slog.Logger
}

type ctxKey struct{}

// New returns the edge handler.
func New(cfg Config) http.Handler {
	proxies := make(map[string]*httputil.ReverseProxy, len(cfg.Routes))
	for name, target := range cfg.Routes {
		rp := httputil.NewSingleHostReverseProxy(target)
		rp.Transport = &http.Transport{ResponseHeaderTimeout: cfg.UpstreamTimeout, Proxy: nil,
			DialContext: (&net.Dialer{Timeout: cfg.UpstreamTimeout}).DialContext}
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

		if !cfg.Public[r.Method+" "+r.URL.Path] {
			token, found := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
			claims, err := cfg.Verifier.Verify(r.Context(), strings.TrimSpace(token))
			if !found || err != nil {
				reason := "missing bearer token"
				if err != nil {
					reason = err.Error()
				}
				cfg.Logger.Info("token_rejected", "reason", reason, "path", r.URL.Path, "correlationId", correlationID)
				w.Header().Set("WWW-Authenticate", `Bearer error="invalid_token"`)
				problem.Write(w, r, http.StatusUnauthorized, "UNAUTHENTICATED", correlationID)
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
