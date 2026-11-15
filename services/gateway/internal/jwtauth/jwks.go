package jwtauth

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"net/http"
)

// HTTPFetcher returns a fetch function that reads the JWKS from url (auth-service, internal route).
func HTTPFetcher(url string, client *http.Client) func(context.Context) (JWKS, error) {
	return func(ctx context.Context) (JWKS, error) {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if err != nil {
			return nil, err
		}
		resp, err := client.Do(req)
		if err != nil {
			return nil, err
		}
		defer func() { _ = resp.Body.Close() }()
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("jwks: status %d", resp.StatusCode)
		}
		var doc struct {
			Keys []struct{ Kty, Kid, N, E string } `json:"keys"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&doc); err != nil {
			return nil, fmt.Errorf("jwks: %w", err)
		}
		keys := JWKS{}
		for _, k := range doc.Keys {
			if k.Kty != "RSA" {
				continue
			}
			n, errN := base64.RawURLEncoding.DecodeString(k.N)
			e, errE := base64.RawURLEncoding.DecodeString(k.E)
			if errN != nil || errE != nil {
				return nil, fmt.Errorf("jwks: invalid key %q", k.Kid)
			}
			keys[k.Kid] = &rsa.PublicKey{N: new(big.Int).SetBytes(n), E: int(new(big.Int).SetBytes(e).Int64())}
		}
		return keys, nil
	}
}
