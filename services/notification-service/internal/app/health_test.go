package app

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

type fakePinger struct{ err error }

func (p fakePinger) Ping(context.Context) error { return p.err }

func get(t *testing.T, h http.Handler, path string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	return rec
}

// The notification-service only consumes (no outbox relay), so readiness reports the database only.
func TestHealth_Ready(t *testing.T) {
	cases := []struct {
		name string
		db   Pinger
		code int
		body string
	}{
		{"database up", fakePinger{}, 200, `{"status":"up","checks":{"db":"up"}}`},
		{"database down is not ready", fakePinger{errors.New("connection refused")}, 503, `{"status":"down","checks":{"db":"down"}}`},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			rec := get(t, NewHealthHandler(c.db, nil), "/health/ready")
			if rec.Code != c.code || rec.Body.String() != c.body {
				t.Fatalf("got %d %s, want %d %s", rec.Code, rec.Body.String(), c.code, c.body)
			}
		})
	}
}

func TestHealth_LiveIgnoresDependencies(t *testing.T) {
	rec := get(t, NewHealthHandler(fakePinger{errors.New("down")}, nil), "/health/live")
	if rec.Code != http.StatusOK || rec.Body.String() != `{"status":"up"}` {
		t.Fatalf("got %d %s", rec.Code, rec.Body.String())
	}
}
