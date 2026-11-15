// Package problem writes application/problem+json errors (docs/padroes.md §4.1).
package problem

import (
	"encoding/json"
	"net/http"
)

// Titles in PT-BR, one per code (padroes §4.1).
var titles = map[string]string{
	"UNAUTHENTICATED":   "Não autenticado",
	"NOT_FOUND":         "Não encontrado",
	"PAYLOAD_TOO_LARGE": "Requisição muito grande",
	"UPSTREAM_TIMEOUT":  "Serviço não respondeu a tempo",
	"BAD_GATEWAY":       "Serviço indisponível",
	"INTERNAL_ERROR":    "Erro interno",
}

// Write sends a problem body with the given status and code.
func Write(w http.ResponseWriter, r *http.Request, status int, code, correlationID string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"type":          "https://ticketpro.local/errors/" + code,
		"title":         titles[code],
		"status":        status,
		"instance":      r.URL.Path,
		"code":          code,
		"correlationId": correlationID,
	})
}
