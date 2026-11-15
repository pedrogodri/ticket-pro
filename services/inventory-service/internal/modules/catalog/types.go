// Package catalog serves the seeded events and sectors (GET /events, GET /events/{id}).
package catalog

import (
	"encoding/base64"
	"errors"
	"strings"
	"time"
)

// Event is a catalog item in the list.
type Event struct {
	ID       string    `json:"id"`
	Name     string    `json:"name"`
	StartsAt time.Time `json:"startsAt"`
	Venue    string    `json:"venue"`
}

// Sector is a sellable area of an event; Available is live stock.
type Sector struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	Capacity       int    `json:"capacity"`
	Available      int    `json:"available"`
	UnitPriceCents int64  `json:"unitPriceCents"`
	Currency       string `json:"currency"`
}

// EventDetail is an event with its sectors.
type EventDetail struct {
	Event
	Sectors []Sector `json:"sectors"`
}

// Cursor is the keyset position (starts_at, id) of the last item of a page.
type Cursor struct {
	StartsAt time.Time
	ID       string
}

// CursorOf returns the cursor after e.
func CursorOf(e Event) Cursor { return Cursor{StartsAt: e.StartsAt, ID: e.ID} }

// Encode returns the opaque cursor sent to clients.
func (c Cursor) Encode() string {
	return base64.RawURLEncoding.EncodeToString([]byte(c.StartsAt.UTC().Format(time.RFC3339Nano) + "|" + c.ID))
}

var errBadCursor = errors.New("invalid cursor")

// DecodeCursor parses an opaque cursor.
func DecodeCursor(s string) (Cursor, error) {
	raw, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return Cursor{}, errBadCursor
	}
	ts, id, ok := strings.Cut(string(raw), "|")
	t, err := time.Parse(time.RFC3339Nano, ts)
	if !ok || err != nil || id == "" {
		return Cursor{}, errBadCursor
	}
	return Cursor{StartsAt: t, ID: id}, nil
}
