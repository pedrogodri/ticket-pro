// Package jwtauth validates the RS256 access tokens issued by auth-service (RFC 0002 §5.5).
package jwtauth

import (
	"context"
	"crypto/rsa"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// ErrInvalidToken is returned for every rejected token; the reason goes only to the log.
var ErrInvalidToken = errors.New("invalid token")

// JWKS maps kid to RSA public key.
type JWKS map[string]*rsa.PublicKey

// Claims are the identity fields the gateway forwards.
type Claims struct {
	Subject string
	Email   string
	ID      string
}

// VerifierConfig holds the RFC 0002 §5.5 settings.
type VerifierConfig struct {
	Issuer             string
	Audience           string
	ClockSkew          time.Duration // JWT_CLOCK_SKEW_S
	CacheTTL           time.Duration // JWKS_CACHE_TTL_S
	RefetchMinInterval time.Duration // unknown kid: at most one refetch per interval
	Now                func() time.Time
}

// Verifier checks alg, kid, signature, iss, aud and exp/nbf against a cached JWKS.
type Verifier struct {
	cfg       VerifierConfig
	fetch     func(context.Context) (JWKS, error)
	mu        sync.Mutex
	keys      JWKS
	fetchedAt time.Time
}

// NewVerifier returns a verifier that loads the JWKS lazily through fetch.
func NewVerifier(cfg VerifierConfig, fetch func(context.Context) (JWKS, error)) *Verifier {
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	return &Verifier{cfg: cfg, fetch: fetch}
}

// Verify returns the token's claims or an error wrapping ErrInvalidToken.
func (v *Verifier) Verify(ctx context.Context, token string) (Claims, error) {
	var mc struct {
		Email string `json:"email"`
		jwt.RegisteredClaims
	}
	parser := jwt.NewParser(
		jwt.WithValidMethods([]string{jwt.SigningMethodRS256.Alg()}), // rejects none and HS*
		jwt.WithIssuer(v.cfg.Issuer),
		jwt.WithAudience(v.cfg.Audience),
		jwt.WithLeeway(v.cfg.ClockSkew),
		jwt.WithExpirationRequired(),
		jwt.WithTimeFunc(v.cfg.Now),
	)
	_, err := parser.ParseWithClaims(token, &mc, func(t *jwt.Token) (any, error) {
		kid, _ := t.Header["kid"].(string)
		if kid == "" {
			return nil, errors.New("missing kid")
		}
		return v.key(ctx, kid)
	})
	if err != nil {
		return Claims{}, fmt.Errorf("%w: %v", ErrInvalidToken, err)
	}
	if mc.Subject == "" {
		return Claims{}, fmt.Errorf("%w: missing sub", ErrInvalidToken)
	}
	return Claims{Subject: mc.Subject, Email: mc.Email, ID: mc.ID}, nil
}

// key returns the public key for kid, refreshing the cache when expired or (rate-limited) on unknown kid.
func (v *Verifier) key(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	now := v.cfg.Now()
	stale := v.keys == nil || now.Sub(v.fetchedAt) > v.cfg.CacheTTL
	if k, ok := v.keys[kid]; ok && !stale {
		return k, nil
	}
	if stale || now.Sub(v.fetchedAt) >= v.cfg.RefetchMinInterval {
		keys, err := v.fetch(ctx)
		v.fetchedAt = now
		if err == nil {
			v.keys = keys
		} else if v.keys == nil {
			return nil, fmt.Errorf("jwks unavailable: %w", err)
		}
	}
	if k, ok := v.keys[kid]; ok {
		return k, nil
	}
	return nil, fmt.Errorf("unknown kid %q", kid)
}
