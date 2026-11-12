// Package app wires configuration, HTTP server and lifecycle of the inventory-service.
package app

import (
	"fmt"
	"log/slog"
	"strconv"
	"strings"
)

// Config is the validated runtime configuration read from environment variables.
type Config struct {
	ServiceName string
	Port        int
	LogLevel    slog.Level
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

	return cfg, nil
}
