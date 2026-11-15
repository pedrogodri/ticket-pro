package app

import (
	"strings"
	"testing"
	"time"
)

func validEnv() map[string]string {
	return map[string]string{
		"SERVICE_NAME":   "gateway",
		"PORT":           "8080",
		"LOG_LEVEL":      "info",
		"GATEWAY_ROUTES": "auth=http://auth-service:8080,orders=http://order-service:8080",
		"JWKS_URL":       "http://auth-service:8080/.well-known/jwks.json",
	}
}

func load(env map[string]string) (Config, error) {
	return LoadConfig(func(k string) string { return env[k] })
}

func TestLoadConfig_RequiredVariables(t *testing.T) {
	for _, name := range []string{"SERVICE_NAME", "PORT", "LOG_LEVEL", "GATEWAY_ROUTES", "JWKS_URL"} {
		env := validEnv()
		delete(env, name)
		if _, err := load(env); err == nil || !strings.Contains(err.Error(), name) {
			t.Errorf("missing %s: want error naming it, got %v", name, err)
		}
	}
}

func TestLoadConfig_InvalidValues(t *testing.T) {
	cases := map[string]string{
		"PORT": "abc", "LOG_LEVEL": "verbose", "GATEWAY_ROUTES": "auth", "GATEWAY_MAX_BODY_BYTES": "0",
	}
	for name, value := range cases {
		env := validEnv()
		env[name] = value
		if _, err := load(env); err == nil || !strings.Contains(err.Error(), name) {
			t.Errorf("%s=%q: want error naming it, got %v", name, value, err)
		}
	}
}

func TestLoadConfig_DefaultsFromRFC0002(t *testing.T) {
	cfg, err := load(validEnv())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Routes["auth"].String() != "http://auth-service:8080" || cfg.Routes["orders"].Host != "order-service:8080" {
		t.Fatalf("routes %v", cfg.Routes)
	}
	if cfg.JWTIssuer != "ticketpro-auth" || cfg.JWTAudience != "ticketpro-api" || cfg.JWKSCacheTTL != 5*time.Minute ||
		cfg.ClockSkew != 30*time.Second || cfg.MaxBodyBytes != 65536 || cfg.UpstreamTimeout != 5*time.Second {
		t.Fatalf("defaults %+v", cfg)
	}
}
