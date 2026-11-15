package app

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func get(t *testing.T, path string) *httptest.ResponseRecorder {
	t.Helper()
	rec := httptest.NewRecorder()
	NewHealthHandler().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	return rec
}

func TestHealthLive(t *testing.T) {
	rec := get(t, "/health/live")
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("content-type %q", ct)
	}
	if body := rec.Body.String(); body != `{"status":"up"}` {
		t.Fatalf("body %q", body)
	}
}

func TestHealthReady(t *testing.T) {
	rec := get(t, "/health/ready")
	if rec.Code != http.StatusOK {
		t.Fatalf("status %d", rec.Code)
	}
	if body := rec.Body.String(); body != `{"status":"up","checks":{}}` {
		t.Fatalf("body %q", body)
	}
}

func TestHealthRejectsPost(t *testing.T) {
	rec := httptest.NewRecorder()
	NewHealthHandler().ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/health/live", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status %d, want 405", rec.Code)
	}
}
