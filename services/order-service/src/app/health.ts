import type { FastifyInstance } from "fastify";

/** Liveness and readiness probes (RFC 0003 §5.9). Dependency checks arrive in F1. */
export function registerHealthRoutes(app: FastifyInstance): void {
  app.get("/health/live", () => ({ status: "up" }));
  app.get("/health/ready", () => ({ status: "up", checks: {} }));
}
