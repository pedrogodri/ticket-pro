// Package httpx holds the HTTP conventions of docs/padroes.md §4: identity from X-User-Id and
// application/problem+json errors.
package httpx

import (
	"context"
	"encoding/json"
	"net/http"
)

// Titles in PT-BR, one per code (padroes §4.1).
var titles = map[string]string{
	"VALIDATION_ERROR": "Requisição inválida",
	"UNAUTHENTICATED":  "Não autenticado",
	"NOT_FOUND":        "Não encontrado",
	"INTERNAL_ERROR":   "Erro interno",
}

type userKey struct{}

// RequireUser rejects requests without X-User-Id (injected by the gateway) with 401 (RFC 0002 §5.6).
func RequireUser(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user := r.Header.Get("X-User-Id")
		if user == "" {
			WriteProblem(w, r, http.StatusUnauthorized, "UNAUTHENTICATED", "")
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), userKey{}, user)))
	})
}

// UserID returns the authenticated user set by RequireUser.
func UserID(ctx context.Context) string {
	id, _ := ctx.Value(userKey{}).(string)
	return id
}

// WriteProblem sends a problem+json body; detail may be empty.
func WriteProblem(w http.ResponseWriter, r *http.Request, status int, code, detail string) {
	body := map[string]any{
		"type":          "https://ticketpro.local/errors/" + code,
		"title":         titles[code],
		"status":        status,
		"instance":      r.URL.Path,
		"code":          code,
		"correlationId": r.Header.Get("X-Correlation-Id"),
	}
	if detail != "" {
		body["detail"] = detail
	}
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// WriteJSON sends a JSON body.
func WriteJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
