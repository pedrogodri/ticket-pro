import Fastify from "fastify";
import type pg from "pg";
import { describe, expect, it } from "vitest";
import { registerOrderRoutes } from "../../../src/modules/orders/http/routes.js";

// Validation happens before any database access: the pool is never touched in these cases.
const app = Fastify();
registerOrderRoutes(app, { pool: {} as pg.Pool, topicArn: "arn:t", maxQuantity: 6 });

const user = { "x-user-id": "0193a6f0-1111-7000-8000-000000000001" };
const key = { "idempotency-key": "0193a6f0-7777-7000-8000-000000000007" };
const body = {
  eventId: "0193a6f0-3333-7000-8000-000000000003",
  sectorId: "0193a6f0-4444-7000-8000-000000000004",
  quantity: 2,
};

async function post(headers: Record<string, string>, payload: unknown) {
  return app.inject({
    method: "POST",
    url: "/orders",
    headers,
    payload: payload as Record<string, unknown>,
  });
}

describe("POST /orders validation (RFC 0002 §5.6, padroes §4)", () => {
  it("requires X-User-Id", async () => {
    const res = await post(key, body);
    expect(res.statusCode).toBe(401);
    expect(res.headers["content-type"]).toMatch(/^application\/problem\+json/);
    expect(res.json()).toMatchObject({ code: "UNAUTHENTICATED", status: 401 });
  });

  it("requires a UUID Idempotency-Key", async () => {
    expect((await post(user, body)).statusCode).toBe(400);
    expect((await post({ ...user, "idempotency-key": "abc" }, body)).statusCode).toBe(400);
  });

  it.each([0, 7, 2.5])("rejects quantity %s", async (quantity) => {
    const res = await post({ ...user, ...key }, { ...body, quantity });
    expect(res.statusCode).toBe(400);
    expect(res.json()).toMatchObject({ code: "VALIDATION_ERROR" });
  });

  // Review Focus 2: identity never comes from the body.
  it("rejects userId (or any unknown field) in the body", async () => {
    const res = await post(
      { ...user, ...key },
      { ...body, userId: "0193a6f0-9999-7000-8000-000000000009" },
    );
    expect(res.statusCode).toBe(400);
  });

  it("GET /orders/{id} with an invalid id is 404, without X-User-Id is 401", async () => {
    expect(
      (await app.inject({ method: "GET", url: "/orders/abc", headers: user })).statusCode,
    ).toBe(404);
    expect(
      (await app.inject({ method: "GET", url: "/orders/0193a6f0-2222-7000-8000-000000000002" }))
        .statusCode,
    ).toBe(401);
  });

  it("GET /orders rejects limit over 50", async () => {
    expect(
      (await app.inject({ method: "GET", url: "/orders?limit=51", headers: user })).statusCode,
    ).toBe(400);
  });
});
