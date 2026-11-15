import Fastify from "fastify";
import { describe, expect, it } from "vitest";
import { registerHealthRoutes, type HealthDeps } from "../../src/app/health.js";

async function get(path: string, deps: HealthDeps) {
  const app = Fastify();
  registerHealthRoutes(app, deps);
  const res = await app.inject({ method: "GET", url: path });
  await app.close();
  return res;
}

const dbUp = (): Promise<void> => Promise.resolve();
const dbDown = (): Promise<void> => Promise.reject(new Error("connection refused"));
const up = (): boolean => true;
const down = (): boolean => false;

describe("health endpoints (RFC 0003 §5.9)", () => {
  it.each([
    [
      "all up",
      { pingDb: dbUp, brokerUp: up },
      200,
      { status: "up", checks: { db: "up", broker: "up" } },
    ],
    [
      "broker down keeps serving (outbox)",
      { pingDb: dbUp, brokerUp: down },
      200,
      { status: "up", checks: { db: "up", broker: "degraded" } },
    ],
    [
      "database down is not ready",
      { pingDb: dbDown, brokerUp: up },
      503,
      { status: "down", checks: { db: "down", broker: "up" } },
    ],
    ["no broker", { pingDb: dbUp }, 200, { status: "up", checks: { db: "up" } }],
  ] as const)("ready: %s", async (_name, deps, code, body) => {
    const res = await get("/health/ready", deps);
    expect(res.statusCode).toBe(code);
    expect(res.headers["content-type"]).toMatch(/^application\/json/);
    expect(res.json()).toEqual(body);
  });

  it("live ignores dependencies", async () => {
    const res = await get("/health/live", { pingDb: dbDown, brokerUp: down });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ status: "up" });
  });
});
