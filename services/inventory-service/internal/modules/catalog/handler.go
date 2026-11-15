package catalog

import (
	"net/http"
	"strconv"

	"github.com/google/uuid"

	"github.com/ticketpro/inventory-service/internal/httpx"
)

const (
	defaultLimit = 20
	maxLimit     = 50 // padroes §4: limit ≤ 50
)

// NewHandler serves GET /events and GET /events/{id} for authenticated users (RFC 0002 §5.6).
func NewHandler(repo Repository) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /events", func(w http.ResponseWriter, r *http.Request) {
		limit := defaultLimit
		if raw := r.URL.Query().Get("limit"); raw != "" {
			n, err := strconv.Atoi(raw)
			if err != nil || n < 1 || n > maxLimit {
				httpx.WriteProblem(w, r, http.StatusBadRequest, "VALIDATION_ERROR", "limit must be between 1 and 50")
				return
			}
			limit = n
		}
		var after *Cursor
		if raw := r.URL.Query().Get("cursor"); raw != "" {
			c, err := DecodeCursor(raw)
			if err != nil {
				httpx.WriteProblem(w, r, http.StatusBadRequest, "VALIDATION_ERROR", "cursor is invalid")
				return
			}
			after = &c
		}
		events, err := repo.ListEvents(r.Context(), limit+1, after)
		if err != nil {
			httpx.WriteProblem(w, r, http.StatusInternalServerError, "INTERNAL_ERROR", "")
			return
		}
		var next *string
		if len(events) > limit {
			events = events[:limit]
			c := CursorOf(events[limit-1]).Encode()
			next = &c
		}
		if events == nil {
			events = []Event{}
		}
		httpx.WriteJSON(w, http.StatusOK, map[string]any{"items": events, "nextCursor": next})
	})
	mux.HandleFunc("GET /events/{id}", func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		if _, err := uuid.Parse(id); err != nil {
			httpx.WriteProblem(w, r, http.StatusNotFound, "NOT_FOUND", "")
			return
		}
		event, ok, err := repo.GetEvent(r.Context(), id)
		switch {
		case err != nil:
			httpx.WriteProblem(w, r, http.StatusInternalServerError, "INTERNAL_ERROR", "")
		case !ok:
			httpx.WriteProblem(w, r, http.StatusNotFound, "NOT_FOUND", "")
		default:
			httpx.WriteJSON(w, http.StatusOK, event)
		}
	})
	return httpx.RequireUser(mux)
}
