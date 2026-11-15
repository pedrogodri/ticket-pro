import { AsyncLocalStorage } from "node:async_hooks";
import { trace } from "@opentelemetry/api";
import { pino, type Logger } from "pino";

export type LogLevel = "debug" | "info" | "warn" | "error";
export type { Logger };

/** Fields every log line of the current async flow carries (correlationId, messageId, type, version…). */
export type LogContext = Record<string, string | number>;

const storage = new AsyncLocalStorage<LogContext>();

/** Runs fn with ctx merged into every log line written inside it. */
export function withLogContext<T>(ctx: LogContext, fn: () => T): T {
  return storage.run({ ...storage.getStore(), ...ctx }, fn);
}

/**
 * JSON logger of docs/padroes.md §5: timestamp (RFC 3339), level (lowercase), service, message, plus the
 * log context and traceId/spanId of the active span. `destination` defaults to stdout.
 */
export function createLogger(
  service: string,
  level: LogLevel,
  destination?: NodeJS.WritableStream,
): Logger {
  const options = {
    level,
    base: { service },
    messageKey: "message",
    timestamp: () => `,"timestamp":"${new Date().toISOString()}"`,
    formatters: { level: (label: string) => ({ level: label }) },
    mixin: () => {
      const span = trace.getActiveSpan()?.spanContext();
      return {
        ...storage.getStore(),
        ...(span ? { traceId: span.traceId, spanId: span.spanId } : {}),
      };
    },
  };
  return destination ? pino(options, destination) : pino(options);
}
