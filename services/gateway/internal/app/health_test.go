package app

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

type pinger struct{ err error }

func (p pinger) Ping(context.Context) error { return p.err }

type broker bool

func (b broker) BrokerUp() bool { return bool(b) }

func get(t *testing.T, h http.Handler, path string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	return rec
}

func TestHealthLive(t *testing.T) {
	rec := get(t, NewHealthHandler(pinger{}, broker(true)), "/health/live")
	if rec.Code != http.StatusOK || rec.Header().Get("Content-Type") != "application/json" || rec.Body.String() != `{"status":"up"}` {
		t.Fatalf("status %d ct %q body %q", rec.Code, rec.Header().Get("Content-Type"), rec.Body)
	}
}

func TestHealthReadyAllUp(t *testing.T) {
	rec := get(t, NewHealthHandler(pinger{}, broker(true)), "/health/ready")
	if rec.Code != http.StatusOK || rec.Body.String() != `{"status":"up","checks":{"redis":"up","broker":"up"}}` {
		t.Fatalf("status %d body %q", rec.Code, rec.Body)
	}
}

// ADR-0014: revocation and rate limit fail open, so the gateway keeps serving without Redis or the broker.
func TestHealthReadyDegradedStillServes(t *testing.T) {
	rec := get(t, NewHealthHandler(pinger{err: errors.New("down")}, broker(false)), "/health/ready")
	if rec.Code != http.StatusOK || rec.Body.String() != `{"status":"degraded","checks":{"redis":"degraded","broker":"degraded"}}` {
		t.Fatalf("status %d body %q", rec.Code, rec.Body)
	}
}

func TestHealthRejectsPost(t *testing.T) {
	rec := httptest.NewRecorder()
	NewHealthHandler(pinger{}, broker(true)).ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/health/live", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status %d, want 405", rec.Code)
	}
}
