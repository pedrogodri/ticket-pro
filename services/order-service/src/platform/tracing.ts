import { context, isSpanContextValid, trace, type Context } from "@opentelemetry/api";
import { AsyncLocalStorageContextManager } from "@opentelemetry/context-async-hooks";

// Without a registered context manager the API is a no-op: context.with() would not carry the active span
// across awaits. Registering is idempotent (a manager installed by the OTel SDK in F4 wins).
context.setGlobalContextManager(new AsyncLocalStorageContextManager().enable());

// W3C trace context: version-traceId-spanId-flags (ADR-0012).
const TRACEPARENT = /^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/;

/** The traceparent of the span in ctx, or undefined when there is none. */
export function inject(ctx: Context = context.active()): string | undefined {
  const span = trace.getSpanContext(ctx);
  if (!span || !isSpanContextValid(span)) return undefined;
  return `00-${span.traceId}-${span.spanId}-${span.traceFlags.toString(16).padStart(2, "0")}`;
}

/** A context carrying the remote span described by traceparent (ctx unchanged when empty or invalid). */
export function extract(traceparent: string | undefined, ctx: Context = context.active()): Context {
  const match = traceparent ? TRACEPARENT.exec(traceparent) : null;
  if (!match?.[1] || !match[2] || !match[3]) return ctx;
  return trace.setSpanContext(ctx, {
    traceId: match[1],
    spanId: match[2],
    traceFlags: Number.parseInt(match[3], 16),
    isRemote: true,
  });
}
