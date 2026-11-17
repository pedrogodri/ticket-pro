// Package app wires configuration, HTTP server and lifecycle of the autoscaler.
package app

import (
	"time"

	"fmt"
	"github.com/ticketpro/autoscaler/internal/scaler"
	"log/slog"
	"strconv"
	"strings"
)

// Config is the validated runtime configuration read from environment variables.
type Config struct {
	ServiceName       string
	Port              int
	LogLevel          slog.Level
	Targets           []scaler.Target // AUTOSCALER_TARGETS: service=queue,...
	Policy            scaler.Policy
	Interval          time.Duration // AUTOSCALER_INTERVAL_S
	Project           string        // COMPOSE_PROJECT_NAME
	DockerSocket      string        // DOCKER_HOST (unix:// only)
	HTTPClientTimeout time.Duration // HTTP_CLIENT_TIMEOUT_MS: SQS and Docker calls
}

// LoadConfig reads and validates configuration. The returned error names the offending variable.
func LoadConfig(getenv func(string) string) (Config, error) {
	var cfg Config

	cfg.ServiceName = strings.TrimSpace(getenv("SERVICE_NAME"))
	if cfg.ServiceName == "" {
		return Config{}, fmt.Errorf("SERVICE_NAME is required")
	}

	rawPort := strings.TrimSpace(getenv("PORT"))
	if rawPort == "" {
		return Config{}, fmt.Errorf("PORT is required")
	}
	port, err := strconv.Atoi(rawPort)
	if err != nil || port < 1 || port > 65535 {
		return Config{}, fmt.Errorf("PORT must be an integer between 1 and 65535, got %q", rawPort)
	}
	cfg.Port = port

	rawLevel := strings.TrimSpace(getenv("LOG_LEVEL"))
	if rawLevel == "" {
		return Config{}, fmt.Errorf("LOG_LEVEL is required")
	}
	switch strings.ToLower(rawLevel) {
	case "debug", "info", "warn", "error":
		if err := cfg.LogLevel.UnmarshalText([]byte(rawLevel)); err != nil {
			return Config{}, fmt.Errorf("LOG_LEVEL is invalid: %w", err)
		}
	default:
		return Config{}, fmt.Errorf("LOG_LEVEL must be one of debug, info, warn, error; got %q", rawLevel)
	}

	if err := loadScaling(getenv, &cfg); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

// loadScaling reads the ADR-0015 settings.
func loadScaling(getenv func(string) string, cfg *Config) error {
	get := func(name, def string) string {
		if v := strings.TrimSpace(getenv(name)); v != "" {
			return v
		}
		return def
	}
	positive := func(name string, def int) (int, error) {
		raw := get(name, strconv.Itoa(def))
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 {
			return 0, fmt.Errorf("%s must be a positive integer, got %q", name, raw)
		}
		return n, nil
	}
	for _, entry := range strings.Split(get("AUTOSCALER_TARGETS", "payment-service=ticketpro-payment-service"), ",") {
		service, q, ok := strings.Cut(strings.TrimSpace(entry), "=")
		if !ok || service == "" || q == "" {
			return fmt.Errorf("AUTOSCALER_TARGETS must be service=queue,...; got %q", entry)
		}
		cfg.Targets = append(cfg.Targets, scaler.Target{Service: service, Queue: q})
	}
	var err error
	ints := []struct {
		name string
		def  int
		dst  *int
	}{
		{"AUTOSCALER_MSGS_PER_REPLICA", 5, &cfg.Policy.MsgsPerReplica},
		{"AUTOSCALER_MIN_REPLICAS", 2, &cfg.Policy.Min},
		{"AUTOSCALER_MAX_REPLICAS", 5, &cfg.Policy.Max},
	}
	for _, f := range ints {
		if *f.dst, err = positive(f.name, f.def); err != nil {
			return err
		}
	}
	if cfg.Policy.Min > cfg.Policy.Max {
		return fmt.Errorf("AUTOSCALER_MIN_REPLICAS (%d) must be <= AUTOSCALER_MAX_REPLICAS (%d)", cfg.Policy.Min, cfg.Policy.Max)
	}
	seconds := func(name string, def int) (time.Duration, error) {
		n, err := positive(name, def)
		return time.Duration(n) * time.Second, err
	}
	if cfg.Policy.Cooldown, err = seconds("AUTOSCALER_COOLDOWN_S", 30); err != nil {
		return err
	}
	if cfg.Interval, err = seconds("AUTOSCALER_INTERVAL_S", 5); err != nil {
		return err
	}
	ms, err := positive("HTTP_CLIENT_TIMEOUT_MS", 3000)
	if err != nil {
		return err
	}
	cfg.HTTPClientTimeout = time.Duration(ms) * time.Millisecond
	cfg.Project = get("COMPOSE_PROJECT_NAME", "ticketpro")
	host := get("DOCKER_HOST", "unix:///var/run/docker.sock")
	socket, ok := strings.CutPrefix(host, "unix://")
	if !ok || socket == "" {
		return fmt.Errorf("DOCKER_HOST must be unix:///path/to/docker.sock; got %q", host)
	}
	cfg.DockerSocket = socket
	return nil
}
