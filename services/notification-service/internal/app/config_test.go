package app

import (
	"log/slog"
	"strings"
	"testing"
	"time"
)

func validEnv() map[string]string {
	return map[string]string{
		"SERVICE_NAME":        "notification-service",
		"PORT":                "8080",
		"LOG_LEVEL":           "info",
		"DATABASE_URL":        "postgres://notifications_app:x@notifications-db:5432/notifications",
		"DATABASE_WORKER_URL": "postgres://notifications_worker:x@notifications-db:5432/notifications",
		"QUEUE_URL":           "http://ministack:4566/000000000000/ticketpro-notification-service",
		"CONTRACTS_DIR":       "/contracts",
		"RECEIPTS_BUCKET":     "ticketpro-receipts",
	}
}

func load(t *testing.T, env map[string]string) (Config, error) {
	t.Helper()
	return LoadConfig(func(k string) string { return env[k] })
}

func TestLoadConfig_RequiredVariables(t *testing.T) {
	for _, name := range []string{"SERVICE_NAME", "PORT", "LOG_LEVEL", "DATABASE_URL", "DATABASE_WORKER_URL", "QUEUE_URL", "CONTRACTS_DIR", "RECEIPTS_BUCKET"} {
		env := validEnv()
		delete(env, name)
		if _, err := load(t, env); err == nil || !strings.Contains(err.Error(), name) {
			t.Errorf("missing %s: want error naming it, got %v", name, err)
		}
	}
}

func TestLoadConfig_InvalidValues(t *testing.T) {
	for name, value := range map[string]string{"PORT": "abc", "LOG_LEVEL": "verbose", "HTTP_CLIENT_TIMEOUT_MS": "-1"} {
		env := validEnv()
		env[name] = value
		if _, err := load(t, env); err == nil || !strings.Contains(err.Error(), name) {
			t.Errorf("%s=%q: want error naming it, got %v", name, value, err)
		}
	}
}

func TestLoadConfig_Defaults(t *testing.T) {
	cfg, err := load(t, validEnv())
	if err != nil {
		t.Fatal(err)
	}
	want := Config{
		ServiceName: "notification-service", Port: 8080, LogLevel: slog.LevelInfo,
		DatabaseURL:       "postgres://notifications_app:x@notifications-db:5432/notifications",
		DatabaseWorkerURL: "postgres://notifications_worker:x@notifications-db:5432/notifications",
		HTTPClientTimeout: 3 * time.Second,
		QueueURL:          "http://ministack:4566/000000000000/ticketpro-notification-service",
		ContractsDir:      "/contracts",
		ReceiptsBucket:    "ticketpro-receipts",
		Consumer:          ConsumerConfig{Concurrency: 4, WaitTimeS: 10},
	}
	if cfg != want {
		t.Fatalf("got %+v\nwant %+v", cfg, want)
	}
}
