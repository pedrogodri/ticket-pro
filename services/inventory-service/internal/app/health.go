package app

import (
	"context"
	"net/http"
	"time"
)

const pingTimeout = 2 * time.Second

// Pinger checks the database (a *pgxpool.Pool satisfies it).
type Pinger interface {
	Ping(ctx context.Context) error
}

// BrokerStatus reports the broker state seen by the outbox relay or a consumer.
type BrokerStatus interface{ BrokerUp() bool }

// NewHealthHandler serves the probes (RFC 0003 §5.9). Readiness is 503 only when the database is down;
// a broker outage is "degraded" because the outbox keeps accepting work. broker may be nil.
func NewHealthHandler(db Pinger, broker BrokerStatus) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, `{"status":"up"}`)
	})
	mux.HandleFunc("GET /health/ready", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), pingTimeout)
		defer cancel()
		status, code, dbState := "up", http.StatusOK, "up"
		if err := db.Ping(ctx); err != nil {
			status, code, dbState = "down", http.StatusServiceUnavailable, "down"
		}
		checks := `"db":"` + dbState + `"`
		if broker != nil {
			brokerState := "up"
			if !broker.BrokerUp() {
				brokerState = "degraded"
			}
			checks += `,"broker":"` + brokerState + `"`
		}
		writeJSON(w, code, `{"status":"`+status+`","checks":{`+checks+`}}`)
	})
	return mux
}

func writeJSON(w http.ResponseWriter, code int, body string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write([]byte(body))
}
