package app

import (
	"log/slog"
	"strings"
	"testing"
)

func envFrom(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

func TestLoadConfig_MissingPort(t *testing.T) {
	_, err := LoadConfig(envFrom(map[string]string{"SERVICE_NAME": "autoscaler", "LOG_LEVEL": "info"}))
	if err == nil || !strings.Contains(err.Error(), "PORT") {
		t.Fatalf("want error naming PORT, got %v", err)
	}
}

func TestLoadConfig_InvalidPort(t *testing.T) {
	_, err := LoadConfig(envFrom(map[string]string{"SERVICE_NAME": "autoscaler", "PORT": "abc", "LOG_LEVEL": "info"}))
	if err == nil || !strings.Contains(err.Error(), "PORT") {
		t.Fatalf("want error naming PORT, got %v", err)
	}
}

func TestLoadConfig_MissingServiceName(t *testing.T) {
	_, err := LoadConfig(envFrom(map[string]string{"PORT": "8080", "LOG_LEVEL": "info"}))
	if err == nil || !strings.Contains(err.Error(), "SERVICE_NAME") {
		t.Fatalf("want error naming SERVICE_NAME, got %v", err)
	}
}

func TestLoadConfig_InvalidLogLevel(t *testing.T) {
	_, err := LoadConfig(envFrom(map[string]string{"SERVICE_NAME": "autoscaler", "PORT": "8080", "LOG_LEVEL": "verbose"}))
	if err == nil || !strings.Contains(err.Error(), "LOG_LEVEL") {
		t.Fatalf("want error naming LOG_LEVEL, got %v", err)
	}
}

func TestLoadConfig_Valid(t *testing.T) {
	cfg, err := LoadConfig(envFrom(map[string]string{"SERVICE_NAME": "autoscaler", "PORT": "8080", "LOG_LEVEL": "info"}))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	want := Config{ServiceName: "autoscaler", Port: 8080, LogLevel: slog.LevelInfo}
	if cfg != want {
		t.Fatalf("got %+v, want %+v", cfg, want)
	}
}
