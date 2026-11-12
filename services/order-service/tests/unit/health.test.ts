import { afterAll, describe, expect, it } from "vitest";
import { buildServer } from "../../src/app/server.js";

const app = buildServer({ serviceName: "order-service", port: 8080, logLevel: "error" });

afterAll(async () => {
  await app.close();
});

describe("health endpoints", () => {
  it("GET /health/live returns up", async () => {
    const res = await app.inject({ method: "GET", url: "/health/live" });
    expect(res.statusCode).toBe(200);
    expect(res.headers["content-type"]).toMatch(/^application\/json/);
    expect(res.json()).toEqual({ status: "up" });
  });

  it("GET /health/ready returns empty checks", async () => {
    const res = await app.inject({ method: "GET", url: "/health/ready" });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ status: "up", checks: {} });
  });

  it("POST /health/live is not routed", async () => {
    const res = await app.inject({ method: "POST", url: "/health/live" });
    expect(res.statusCode).toBe(404);
  });
});
