import Fastify, { type FastifyInstance } from "fastify";
import type { Config } from "./config.js";
import { registerHealthRoutes } from "./health.js";

/**
 * Builds the HTTP server. Logs follow docs/padroes.md §5: one JSON line with
 * timestamp (RFC 3339), level (lowercase), service and message.
 * `destination` defaults to stdout; tests pass their own stream.
 */
export function buildServer(config: Config, destination?: NodeJS.WritableStream): FastifyInstance {
  const app = Fastify({
    logger: {
      level: config.logLevel,
      base: { service: config.serviceName },
      messageKey: "message",
      timestamp: () => `,"timestamp":"${new Date().toISOString()}"`,
      formatters: {
        level: (label) => ({ level: label }),
      },
      ...(destination ? { stream: destination } : {}),
    },
  });
  registerHealthRoutes(app);
  return app;
}
