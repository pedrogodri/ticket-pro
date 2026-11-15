// Package app wires configuration, HTTP server and lifecycle of the inventory-service.
package app

import (
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"time"
)

// Config is the validated runtime configuration read from environment variables.
type Config struct {
	ServiceName       string
	Port              int
	LogLevel          slog.Level
	DatabaseURL       string // API role, RLS applies (docs/padroes.md §6.1)
	DatabaseWorkerURL string // worker role: relay, consumers, jobs
	TopicARN          string // INVENTORY_TOPIC_ARN
	Outbox            OutboxConfig
	HTTPClientTimeout time.Duration
	QueueURL          string // own SQS queue
	ContractsDir      string // vendored contracts (message validation)
	Consumer          ConsumerConfig
	ReservationTTL    time.Duration // RESERVATION_TTL_MS
}

// ConsumerConfig tunes the SQS consumer (RFC 0003 §5.1).
type ConsumerConfig struct {
	Concurrency int
	WaitTimeS   int
}

// OutboxConfig tunes the outbox relay (RFC 0003 §5.1).
type OutboxConfig struct {
	PollInterval time.Duration
	BatchSize    int
	MaxBackoff   time.Duration
	ClaimLease   time.Duration
}

// LoadConfig reads and validates configuration. The returned error names the offending variable.
func LoadConfig(getenv func(string) string) (Config, error) {
	r := reader{getenv: getenv}
	cfg := Config{
		ServiceName:       r.required("SERVICE_NAME"),
		Port:              r.port("PORT"),
		LogLevel:          r.level("LOG_LEVEL"),
		DatabaseURL:       r.required("DATABASE_URL"),
		DatabaseWorkerURL: r.required("DATABASE_WORKER_URL"),
		TopicARN:          r.required("INVENTORY_TOPIC_ARN"),
		Outbox: OutboxConfig{
			PollInterval: r.millis("OUTBOX_POLL_INTERVAL_MS", 200),
			BatchSize:    r.positiveInt("OUTBOX_BATCH_SIZE", 50),
			MaxBackoff:   r.millis("OUTBOX_MAX_BACKOFF_MS", 30000),
			ClaimLease:   r.millis("OUTBOX_CLAIM_LEASE_MS", 30000),
		},
		HTTPClientTimeout: r.millis("HTTP_CLIENT_TIMEOUT_MS", 3000),
		QueueURL:          r.required("QUEUE_URL"),
		ContractsDir:      r.required("CONTRACTS_DIR"),
		Consumer: ConsumerConfig{
			Concurrency: r.positiveInt("CONSUMER_CONCURRENCY", 4),
			WaitTimeS:   r.positiveInt("SQS_WAIT_TIME_S", 10),
		},
		ReservationTTL: r.millis("RESERVATION_TTL_MS", 60000),
	}
	if r.err != nil {
		return Config{}, r.err
	}
	return cfg, nil
}

// reader keeps the first validation error so LoadConfig reads like a declaration.
type reader struct {
	getenv func(string) string
	err    error
}

func (r *reader) fail(format string, args ...any) {
	if r.err == nil {
		r.err = fmt.Errorf(format, args...)
	}
}

func (r *reader) required(name string) string {
	v := strings.TrimSpace(r.getenv(name))
	if v == "" {
		r.fail("%s is required", name)
	}
	return v
}

func (r *reader) port(name string) int {
	raw := r.required(name)
	if raw == "" {
		return 0
	}
	port, err := strconv.Atoi(raw)
	if err != nil || port < 1 || port > 65535 {
		r.fail("%s must be an integer between 1 and 65535, got %q", name, raw)
	}
	return port
}

func (r *reader) level(name string) slog.Level {
	raw := r.required(name)
	var level slog.Level
	switch strings.ToLower(raw) {
	case "":
	case "debug", "info", "warn", "error":
		_ = level.UnmarshalText([]byte(raw))
	default:
		r.fail("%s must be one of debug, info, warn, error; got %q", name, raw)
	}
	return level
}

func (r *reader) positiveInt(name string, def int) int {
	raw := strings.TrimSpace(r.getenv(name))
	if raw == "" {
		return def
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n < 1 {
		r.fail("%s must be a positive integer, got %q", name, raw)
	}
	return n
}

func (r *reader) millis(name string, def int) time.Duration {
	return time.Duration(r.positiveInt(name, def)) * time.Millisecond
}
