package app

import (
	"strings"
	"testing"
	"time"

	"github.com/ticketpro/autoscaler/internal/scaler"
)

func baseEnv() map[string]string {
	return map[string]string{"SERVICE_NAME": "autoscaler", "PORT": "8080", "LOG_LEVEL": "info"}
}

func TestLoadConfig_ScalingDefaultsFromADR0015(t *testing.T) {
	cfg, err := LoadConfig(envFrom(baseEnv()))
	if err != nil {
		t.Fatal(err)
	}
	want := scaler.Policy{MsgsPerReplica: 5, Min: 2, Max: 5, Cooldown: 30 * time.Second}
	if cfg.Policy != want || cfg.Interval != 5*time.Second || cfg.Project != "ticketpro" ||
		cfg.DockerSocket != "/var/run/docker.sock" || cfg.HTTPClientTimeout != 3*time.Second {
		t.Fatalf("config %+v", cfg)
	}
	if len(cfg.Targets) != 1 || cfg.Targets[0] != (scaler.Target{Service: "payment-service", Queue: "ticketpro-payment-service"}) {
		t.Fatalf("targets %+v", cfg.Targets)
	}
}

func TestLoadConfig_ScalingOverrides(t *testing.T) {
	env := baseEnv()
	env["AUTOSCALER_TARGETS"] = "payment-service=ticketpro-payment-service, inventory-service=ticketpro-inventory-service"
	env["AUTOSCALER_MAX_REPLICAS"] = "8"
	env["DOCKER_HOST"] = "unix:///run/docker.sock"
	cfg, err := LoadConfig(envFrom(env))
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Targets) != 2 || cfg.Targets[1].Queue != "ticketpro-inventory-service" || cfg.Policy.Max != 8 || cfg.DockerSocket != "/run/docker.sock" {
		t.Fatalf("config %+v", cfg)
	}
}

func TestLoadConfig_ScalingInvalidValues(t *testing.T) {
	cases := map[string]string{
		"AUTOSCALER_TARGETS":          "payment-service",
		"AUTOSCALER_INTERVAL_S":       "0",
		"AUTOSCALER_MSGS_PER_REPLICA": "-1",
		"AUTOSCALER_MIN_REPLICAS":     "6", // > MAX (5)
		"DOCKER_HOST":                 "tcp://docker:2375",
	}
	for name, value := range cases {
		env := baseEnv()
		env[name] = value
		if _, err := LoadConfig(envFrom(env)); err == nil || !strings.Contains(err.Error(), name) {
			t.Errorf("%s=%q: want error naming it, got %v", name, value, err)
		}
	}
}
