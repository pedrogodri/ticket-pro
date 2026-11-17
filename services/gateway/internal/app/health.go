package app

import (
	"context"
	"net/http"
	"time"
)

const pingTimeout = 2 * time.Second

// Pinger checks the gateway's Redis (a *redis.Client wrapped by RedisPinger satisfies it).
type Pinger interface {
	Ping(ctx context.Context) error
}

// BrokerStatus reports the broker state seen by the TokenRevoked consumer.
type BrokerStatus interface{ BrokerUp() bool }

// NewHealthHandler serves the probes (RFC 0003 §5.9). Revocation and rate limit fail open (ADR-0014),
// so a Redis or broker outage is "degraded" and readiness stays 200: the gateway keeps routing.
func NewHealthHandler(redis Pinger, broker BrokerStatus) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, `{"status":"up"}`)
	})
	mux.HandleFunc("GET /health/ready", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), pingTimeout)
		defer cancel()
		redisState, brokerState := "up", "up"
		if err := redis.Ping(ctx); err != nil {
			redisState = "degraded"
		}
		if !broker.BrokerUp() {
			brokerState = "degraded"
		}
		status := "up"
		if redisState != "up" || brokerState != "up" {
			status = "degraded"
		}
		writeJSON(w, `{"status":"`+status+`","checks":{"redis":"`+redisState+`","broker":"`+brokerState+`"}}`)
	})
	return mux
}

func writeJSON(w http.ResponseWriter, body string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(body))
}
