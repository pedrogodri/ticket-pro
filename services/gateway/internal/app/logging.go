package app

import (
	"io"
	"log/slog"
	"strings"
	"time"
)

// NewLogger returns a JSON logger following docs/padroes.md §5:
// {"timestamp": RFC 3339 UTC, "level": lowercase, "service": ..., "message": ...}.
func NewLogger(w io.Writer, cfg Config) *slog.Logger {
	handler := slog.NewJSONHandler(w, &slog.HandlerOptions{
		Level:       cfg.LogLevel,
		ReplaceAttr: standardKeys,
	})
	return slog.New(handler).With("service", cfg.ServiceName)
}

func standardKeys(groups []string, a slog.Attr) slog.Attr {
	if len(groups) > 0 {
		return a
	}
	switch a.Key {
	case slog.TimeKey:
		return slog.String("timestamp", a.Value.Time().UTC().Format(time.RFC3339Nano))
	case slog.LevelKey:
		return slog.String("level", strings.ToLower(a.Value.String()))
	case slog.MessageKey:
		return slog.Attr{Key: "message", Value: a.Value}
	}
	return a
}
