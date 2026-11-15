import type { FastifyInstance } from "fastify";

/** Dependencies checked by readiness. brokerUp is absent for services without an outbox relay. */
export interface HealthDeps {
  pingDb: () => Promise<void>;
  brokerUp?: () => boolean;
}

/**
 * Liveness and readiness probes (RFC 0003 §5.9). Readiness is 503 only when the database is down; a
 * broker outage is "degraded" because the outbox keeps accepting work.
 */
export function registerHealthRoutes(app: FastifyInstance, deps: HealthDeps): void {
  app.get("/health/live", () => ({ status: "up" }));
  app.get("/health/ready", async (_request, reply) => {
    const dbUp = await deps.pingDb().then(
      () => true,
      () => false,
    );
    const checks: Record<string, string> = { db: dbUp ? "up" : "down" };
    if (deps.brokerUp) checks["broker"] = deps.brokerUp() ? "up" : "degraded";
    return reply.code(dbUp ? 200 : 503).send({ status: dbUp ? "up" : "down", checks });
  });
}
