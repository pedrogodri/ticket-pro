package app

import "net/http"

// NewHealthHandler serves the liveness and readiness probes (RFC 0003 §5.9).
// Readiness has no dependency checks yet; database and broker checks arrive in F1.
func NewHealthHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", writeJSON(`{"status":"up"}`))
	mux.HandleFunc("GET /health/ready", writeJSON(`{"status":"up","checks":{}}`))
	return mux
}

func writeJSON(body string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(body))
	}
}
