import Fastify, { type FastifyInstance } from "fastify";
import type { Config } from "./config.js";
import { registerHealthRoutes } from "./health.js";

/** Builds the HTTP server with JSON logs (pino) tagged with the service name. */
export function buildServer(config: Config): FastifyInstance {
  const app = Fastify({
    logger: {
      level: config.logLevel,
      base: { service: config.serviceName },
      timestamp: () => `,"timestamp":"${new Date().toISOString()}"`,
      messageKey: "message",
    },
  });
  registerHealthRoutes(app);
  return app;
}
