package tracing

import (
	"context"
	"testing"
	"time"
)

func TestSetupWithoutEndpointIsANoop(t *testing.T) {
	shutdown, err := Setup(context.Background(), Config{ServiceName: "svc"})
	if err != nil {
		t.Fatal(err)
	}
	if err := shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}
}

// Review Focus 2: an unreachable collector must not block startup (export is asynchronous).
func TestSetupWithUnreachableCollectorReturnsFast(t *testing.T) {
	start := time.Now()
	shutdown, err := Setup(context.Background(), Config{ServiceName: "svc", Endpoint: "127.0.0.1:1"})
	if err != nil {
		t.Fatal(err)
	}
	if time.Since(start) > time.Second {
		t.Fatalf("Setup took %v", time.Since(start))
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_ = shutdown(ctx) // flush fails against a closed port; it must still return
}
