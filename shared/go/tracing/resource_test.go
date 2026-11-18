package tracing

import "testing"

// The resource keeps the SDK defaults (telemetry.sdk.*, OTEL_RESOURCE_ATTRIBUTES) next to service.name.
func TestResourceKeepsSDKDefaults(t *testing.T) {
	t.Setenv("OTEL_RESOURCE_ATTRIBUTES", "deployment.environment=demo")
	res := newResource("inventory-service")
	got := map[string]string{}
	for _, kv := range res.Attributes() {
		got[string(kv.Key)] = kv.Value.AsString()
	}
	if got["service.name"] != "inventory-service" || got["telemetry.sdk.language"] != "go" || got["deployment.environment"] != "demo" {
		t.Fatalf("resource attributes %v", got)
	}
}
