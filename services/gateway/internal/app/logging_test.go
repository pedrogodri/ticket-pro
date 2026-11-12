package app

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"testing"
	"time"
)

// docs/padroes.md §5: one JSON line with timestamp, level (lowercase), service, message.
func TestNewLogger_UsesStandardSchema(t *testing.T) {
	var buf bytes.Buffer
	logger := NewLogger(&buf, Config{ServiceName: "gateway", Port: 8080, LogLevel: slog.LevelInfo})

	logger.Warn("http_server_started", "port", 8080)

	var line map[string]any
	if err := json.Unmarshal(buf.Bytes(), &line); err != nil {
		t.Fatalf("not a JSON line: %q (%v)", buf.String(), err)
	}
	if line["level"] != "warn" {
		t.Errorf("level = %v, want warn", line["level"])
	}
	if line["message"] != "http_server_started" {
		t.Errorf("message = %v", line["message"])
	}
	if line["service"] != "gateway" {
		t.Errorf("service = %v", line["service"])
	}
	ts, ok := line["timestamp"].(string)
	if !ok {
		t.Fatalf("timestamp missing: %v", line)
	}
	if _, err := time.Parse(time.RFC3339Nano, ts); err != nil {
		t.Errorf("timestamp %q is not RFC 3339: %v", ts, err)
	}
	for _, legacy := range []string{"time", "msg"} {
		if _, found := line[legacy]; found {
			t.Errorf("unexpected key %q in %v", legacy, line)
		}
	}
}

func TestNewLogger_RespectsLevel(t *testing.T) {
	var buf bytes.Buffer
	logger := NewLogger(&buf, Config{ServiceName: "gateway", Port: 8080, LogLevel: slog.LevelWarn})
	logger.Info("ignored")
	if buf.Len() != 0 {
		t.Fatalf("info logged at warn level: %q", buf.String())
	}
}
