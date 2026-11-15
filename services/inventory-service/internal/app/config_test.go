package app

import (
	"log/slog"
	"strings"
	"testing"
	"time"
)

func validEnv() map[string]string {
	return map[string]string{
		"SERVICE_NAME":        "inventory-service",
		"PORT":                "8080",
		"LOG_LEVEL":           "info",
		"DATABASE_URL":        "postgres://inventory_app:x@inventory-db:5432/inventory",
		"DATABASE_WORKER_URL": "postgres://inventory_worker:x@inventory-db:5432/inventory",
		"INVENTORY_TOPIC_ARN": "arn:aws:sns:us-east-1:000000000000:ticketpro-inventory",
		"QUEUE_URL":           "http://ministack:4566/000000000000/ticketpro-inventory-service",
		"CONTRACTS_DIR":       "/contracts",
	}
}

func load(t *testing.T, env map[string]string) (Config, error) {
	t.Helper()
	return LoadConfig(func(k string) string { return env[k] })
}

func TestLoadConfig_RequiredVariables(t *testing.T) {
	for _, name := range []string{"SERVICE_NAME", "PORT", "LOG_LEVEL", "DATABASE_URL", "DATABASE_WORKER_URL", "INVENTORY_TOPIC_ARN", "QUEUE_URL", "CONTRACTS_DIR"} {
		env := validEnv()
		delete(env, name)
		if _, err := load(t, env); err == nil || !strings.Contains(err.Error(), name) {
			t.Errorf("missing %s: want error naming it, got %v", name, err)
		}
	}
}

func TestLoadConfig_InvalidValues(t *testing.T) {
	cases := map[string]string{"PORT": "abc", "LOG_LEVEL": "verbose", "OUTBOX_BATCH_SIZE": "0", "OUTBOX_POLL_INTERVAL_MS": "soon"}
	for name, value := range cases {
		env := validEnv()
		env[name] = value
		if _, err := load(t, env); err == nil || !strings.Contains(err.Error(), name) {
			t.Errorf("%s=%q: want error naming it, got %v", name, value, err)
		}
	}
}

func TestLoadConfig_DefaultsFromRFC0003(t *testing.T) {
	cfg, err := load(t, validEnv())
	if err != nil {
		t.Fatal(err)
	}
	want := Config{
		ServiceName: "inventory-service", Port: 8080, LogLevel: slog.LevelInfo,
		DatabaseURL:       "postgres://inventory_app:x@inventory-db:5432/inventory",
		DatabaseWorkerURL: "postgres://inventory_worker:x@inventory-db:5432/inventory",
		TopicARN:          "arn:aws:sns:us-east-1:000000000000:ticketpro-inventory",
		Outbox: OutboxConfig{
			PollInterval: 200 * time.Millisecond, BatchSize: 50,
			MaxBackoff: 30 * time.Second, ClaimLease: 30 * time.Second,
		},
		HTTPClientTimeout: 3 * time.Second,
		QueueURL:          "http://ministack:4566/000000000000/ticketpro-inventory-service",
		ContractsDir:      "/contracts",
		Consumer:          ConsumerConfig{Concurrency: 4, WaitTimeS: 10},
		ReservationTTL:    time.Minute,
	}
	if cfg != want {
		t.Fatalf("got %+v\nwant %+v", cfg, want)
	}
}

func TestLoadConfig_Overrides(t *testing.T) {
	env := validEnv()
	env["OUTBOX_BATCH_SIZE"] = "10"
	env["OUTBOX_MAX_BACKOFF_MS"] = "1000"
	env["HTTP_CLIENT_TIMEOUT_MS"] = "500"
	cfg, err := load(t, env)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Outbox.BatchSize != 10 || cfg.Outbox.MaxBackoff != time.Second || cfg.HTTPClientTimeout != 500*time.Millisecond {
		t.Fatalf("overrides not applied: %+v", cfg)
	}
}
