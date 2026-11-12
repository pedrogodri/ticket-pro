package logging

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"testing"
	"time"
)

func TestP06_StandardKeysAndCorrelationID(t *testing.T) {
	var buf bytes.Buffer
	logger := New(&buf, "inventory-service", slog.LevelInfo)

	logger.InfoContext(WithCorrelationID(context.Background(), "c-1"), "message_consumed")

	var line map[string]any
	if err := json.Unmarshal(buf.Bytes(), &line); err != nil {
		t.Fatalf("not one JSON line: %q", buf.String())
	}
	if _, err := time.Parse(time.RFC3339Nano, line["timestamp"].(string)); err != nil {
		t.Fatalf("timestamp: %v", err)
	}
	want := map[string]any{"level": "info", "service": "inventory-service", "message": "message_consumed", "correlationId": "c-1"}
	for k, v := range want {
		if line[k] != v {
			t.Fatalf("%s = %v, want %v (line %v)", k, line[k], v, line)
		}
	}
	for _, k := range []string{"time", "msg"} {
		if _, ok := line[k]; ok {
			t.Fatalf("non-standard key %q present", k)
		}
	}

	buf.Reset()
	logger.Info("no_context")
	if bytes.Contains(buf.Bytes(), []byte("correlationId")) {
		t.Fatalf("correlationId must be absent without context: %s", buf.String())
	}
}
