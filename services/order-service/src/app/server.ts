import Fastify, { type FastifyBaseLogger, type FastifyInstance } from "fastify";
import { createLogger } from "../platform/logging.js";
import type { Config } from "./config.js";
import { registerHealthRoutes, type HealthDeps } from "./health.js";

/**
 * Builds the HTTP server. Logs follow docs/padroes.md §5 through the platform logger.
 * `destination` defaults to stdout; tests pass their own stream.
 */
export function buildServer(
  config: Pick<Config, "serviceName" | "logLevel">,
  health: HealthDeps,
  destination?: NodeJS.WritableStream,
): FastifyInstance {
  const loggerInstance: FastifyBaseLogger = createLogger(
    config.serviceName,
    config.logLevel,
    destination,
  );
  const app = Fastify({ loggerInstance });
  registerHealthRoutes(app, health);
  return app;
}
