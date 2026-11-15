package jwtauth

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

var now = time.Date(2026, 11, 20, 18, 30, 0, 0, time.UTC)

func newKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func sign(t *testing.T, key *rsa.PrivateKey, kid string, claims jwt.MapClaims) string {
	t.Helper()
	tok := jwt.NewWithClaims(jwt.SigningMethodRS256, claims)
	tok.Header["kid"] = kid
	s, err := tok.SignedString(key)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func validClaims() jwt.MapClaims {
	return jwt.MapClaims{
		"iss": "ticketpro-auth", "aud": "ticketpro-api", "sub": "0193a6f0-1111-7000-8000-000000000001",
		"jti": "j-1", "email": "ana@exemplo.com", "iat": now.Unix(), "exp": now.Add(15 * time.Minute).Unix(),
	}
}

func verifier(key *rsa.PrivateKey, fetches *atomic.Int32) *Verifier {
	return NewVerifier(VerifierConfig{
		Issuer: "ticketpro-auth", Audience: "ticketpro-api", ClockSkew: 30 * time.Second,
		CacheTTL: 5 * time.Minute, RefetchMinInterval: 10 * time.Second, Now: func() time.Time { return now },
	}, func(context.Context) (JWKS, error) {
		fetches.Add(1)
		return JWKS{"kid-1": &key.PublicKey}, nil
	})
}

func TestVerify_ValidTokenGivesClaims(t *testing.T) {
	key := newKey(t)
	var fetches atomic.Int32
	claims, err := verifier(key, &fetches).Verify(context.Background(), sign(t, key, "kid-1", validClaims()))
	if err != nil {
		t.Fatal(err)
	}
	if claims.Subject != "0193a6f0-1111-7000-8000-000000000001" || claims.Email != "ana@exemplo.com" || claims.ID != "j-1" {
		t.Fatalf("claims %+v", claims)
	}
}

// Review Focus 1: every tampered or foreign token is rejected.
func TestVerify_RejectsTamperedTokens(t *testing.T) {
	key := newKey(t)
	other := newKey(t)
	var fetches atomic.Int32
	v := verifier(key, &fetches)

	expired := validClaims()
	expired["exp"] = now.Add(-31 * time.Second).Unix()
	wrongAud := validClaims()
	wrongAud["aud"] = "someone-else"
	wrongIss := validClaims()
	wrongIss["iss"] = "evil"

	none := jwt.NewWithClaims(jwt.SigningMethodNone, validClaims())
	none.Header["kid"] = "kid-1"
	noneToken, _ := none.SignedString(jwt.UnsafeAllowNoneSignatureType)

	// HS256 signed with the public key bytes (classic algorithm-confusion attack).
	hs := jwt.NewWithClaims(jwt.SigningMethodHS256, validClaims())
	hs.Header["kid"] = "kid-1"
	hsToken, _ := hs.SignedString([]byte(key.N.String()))

	cases := map[string]string{
		"empty":       "",
		"garbage":     "a.b.c",
		"alg none":    noneToken,
		"hs256":       hsToken,
		"wrong key":   sign(t, other, "kid-1", validClaims()),
		"expired":     sign(t, key, "kid-1", expired),
		"wrong aud":   sign(t, key, "kid-1", wrongAud),
		"wrong iss":   sign(t, key, "kid-1", wrongIss),
		"unknown kid": sign(t, key, "kid-9", validClaims()),
	}
	for name, token := range cases {
		if _, err := v.Verify(context.Background(), token); !errors.Is(err, ErrInvalidToken) {
			t.Errorf("%s: want ErrInvalidToken, got %v", name, err)
		}
	}
}

func TestVerify_ClockSkewIsTolerated(t *testing.T) {
	key := newKey(t)
	var fetches atomic.Int32
	c := validClaims()
	c["exp"] = now.Add(-20 * time.Second).Unix()
	if _, err := verifier(key, &fetches).Verify(context.Background(), sign(t, key, "kid-1", c)); err != nil {
		t.Fatalf("20s past exp is within the 30s skew: %v", err)
	}
}

func TestVerify_UnknownKidRefetchesAtMostOncePerInterval(t *testing.T) {
	key := newKey(t)
	var fetches atomic.Int32
	v := verifier(key, &fetches)
	token := sign(t, key, "kid-9", validClaims())
	for i := 0; i < 5; i++ {
		_, _ = v.Verify(context.Background(), token)
	}
	if n := fetches.Load(); n > 2 {
		t.Fatalf("unknown kid triggered %d fetches; want the initial fetch plus at most one refetch", n)
	}
}
