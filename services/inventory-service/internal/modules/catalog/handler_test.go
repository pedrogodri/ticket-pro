package catalog

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

type fakeRepo struct{}

func (fakeRepo) ListEvents(context.Context, int, *Cursor) ([]Event, error) { return nil, nil }

func (fakeRepo) GetEvent(context.Context, string) (EventDetail, bool, error) {
	return EventDetail{}, false, nil
}

func get(path string, user bool) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, path, nil)
	if user {
		req.Header.Set("X-User-Id", "0193a6f0-1111-7000-8000-000000000001")
	}
	rec := httptest.NewRecorder()
	NewHandler(fakeRepo{}).ServeHTTP(rec, req)
	return rec
}

func TestHandler_RequiresUser(t *testing.T) {
	if rec := get("/events", false); rec.Code != http.StatusUnauthorized {
		t.Fatalf("status %d, want 401", rec.Code)
	}
}

func TestHandler_NotFoundAndValidation(t *testing.T) {
	cases := map[string]int{
		"/events/0193a6f0-3333-7000-8000-000000000003": 404,
		"/events/abc":           404,
		"/events?limit=51":      400,
		"/events?limit=0":       400,
		"/events?cursor=bm9wZQ": 400, // base64 of "nope": decodes, but is not a cursor
		"/events":               200,
	}
	for path, want := range cases {
		rec := get(path, true)
		if rec.Code != want {
			t.Errorf("%s: status %d, want %d", path, rec.Code, want)
		}
		if want != 200 && rec.Header().Get("Content-Type") != "application/problem+json" {
			t.Errorf("%s: content-type %q", path, rec.Header().Get("Content-Type"))
		}
	}
}
