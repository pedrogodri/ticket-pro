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

type fakeBroker struct{ up bool }

func (b fakeBroker) BrokerUp() bool { return b.up }

func get(t *testing.T, h http.Handler, path string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	return rec
}

func TestHealth_Ready(t *testing.T) {
	down := errors.New("connection refused")
	cases := []struct {
		name   string
		db     Pinger
		broker BrokerStatus
		code   int
		body   string
	}{
		{"all up", fakePinger{}, fakeBroker{true}, 200, `{"status":"up","checks":{"db":"up","broker":"up"}}`},
		{"broker down keeps serving (outbox)", fakePinger{}, fakeBroker{false}, 200, `{"status":"up","checks":{"db":"up","broker":"degraded"}}`},
		{"database down is not ready", fakePinger{down}, fakeBroker{true}, 503, `{"status":"down","checks":{"db":"down","broker":"up"}}`},
		{"no broker", fakePinger{}, nil, 200, `{"status":"up","checks":{"db":"up"}}`},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			rec := get(t, NewHealthHandler(c.db, c.broker), "/health/ready")
			if rec.Code != c.code || rec.Body.String() != c.body {
				t.Fatalf("got %d %s, want %d %s", rec.Code, rec.Body.String(), c.code, c.body)
			}
			if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
				t.Fatalf("content-type %q", ct)
			}
		})
	}
}

func TestHealth_LiveIgnoresDependencies(t *testing.T) {
	rec := get(t, NewHealthHandler(fakePinger{errors.New("down")}, fakeBroker{false}), "/health/live")
	if rec.Code != http.StatusOK || rec.Body.String() != `{"status":"up"}` {
		t.Fatalf("got %d %s", rec.Code, rec.Body.String())
	}
}

func TestHealth_RejectsPost(t *testing.T) {
	rec := httptest.NewRecorder()
	NewHealthHandler(fakePinger{}, nil).ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/health/live", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status %d, want 405", rec.Code)
	}
}
