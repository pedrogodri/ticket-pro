// Package tracing propagates W3C trace context through messages (ADR-0012).
package tracing

import (
	"context"

	"go.opentelemetry.io/otel/propagation"
)

const header = "traceparent"

var propagator = propagation.TraceContext{}

// Inject returns the traceparent of the span in ctx, or "" when there is none.
func Inject(ctx context.Context) string {
	carrier := propagation.MapCarrier{}
	propagator.Inject(ctx, carrier)
	return carrier.Get(header)
}

// Extract returns ctx carrying the remote span context described by traceparent (no-op when empty or invalid).
func Extract(ctx context.Context, traceparent string) context.Context {
	if traceparent == "" {
		return ctx
	}
	return propagator.Extract(ctx, propagation.MapCarrier{header: traceparent})
}
