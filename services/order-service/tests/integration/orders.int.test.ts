import Fastify, { type FastifyInstance } from "fastify";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { registerOrderRoutes } from "../../src/modules/orders/http/routes.js";
import { createValidator, type ContractValidator } from "../../src/platform/validator.js";
import { startDatabase, type TestDatabase } from "../support/database.js";

const TOPIC = "arn:aws:sns:us-east-1:000000000000:ticketpro-orders";
const A = "0193a6f0-1111-7000-8000-000000000001";
const B = "0193a6f0-1111-7000-8000-0000000000bb";
const body = {
  eventId: "0193a6f0-3333-7000-8000-000000000003",
  sectorId: "0193a6f0-4444-7000-8000-000000000004",
  quantity: 2,
};

let db: TestDatabase;
let app: FastifyInstance;
let validator: ContractValidator;
let appV2: FastifyInstance;

beforeAll(async () => {
  db = await startDatabase();
  validator = await createValidator("contracts");
  app = Fastify();
  registerOrderRoutes(app, {
    pool: db.appPool,
    topicArn: TOPIC,
    maxQuantity: 6,
    orderCreatedVersion: 1,
  });
  appV2 = Fastify();
  registerOrderRoutes(appV2, {
    pool: db.appPool,
    topicArn: TOPIC,
    maxQuantity: 6,
    orderCreatedVersion: 2,
  });
}, 120_000);

afterAll(async () => {
  await app.close();
  await appV2.close();
  await db.stop();
});

const create = (user: string, key: string, server: FastifyInstance = app) =>
  server.inject({
    method: "POST",
    url: "/orders",
    headers: {
      "x-user-id": user,
      "idempotency-key": key,
      "x-correlation-id": "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c",
    },
    payload: body,
  });

describe("orders API against Postgres (orders_app role, RLS)", () => {
  it("accepts an order with 202, Location and a valid OrderCreated in the outbox", async () => {
    const res = await create(A, crypto.randomUUID());
    expect(res.statusCode).toBe(202);
    const { orderId, status } = res.json<{ orderId: string; status: string }>();
    expect(status).toBe("PENDING");
    expect(res.headers["location"]).toBe(`/orders/${orderId}`);
    const out = await db.ownerPool.query<{
      message_id: string;
      correlation_id: string;
      type: string;
      version: number;
      occurred_at: Date;
      payload: Record<string, unknown>;
    }>(
      "SELECT message_id, correlation_id, type, version, occurred_at, payload FROM outbox WHERE payload->>'orderId' = $1",
      [orderId],
    );
    expect(out.rowCount).toBe(1);
    const row = out.rows[0];
    if (!row) throw new Error("OrderCreated not in outbox");
    expect(row.correlation_id).toBe("c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c");
    validator.validate(
      JSON.stringify({
        messageId: row.message_id,
        correlationId: row.correlation_id,
        type: row.type,
        version: row.version,
        occurredAt: row.occurred_at.toISOString(),
        payload: row.payload,
      }),
    );
    expect(row.version).toBe(1);
    expect(row.payload).toEqual({ orderId, userId: A, ...body });
  });

  // RFC 0001 §5.5: ORDER_CREATED_VERSION=2 publishes v2 with channel API, valid against its contract.
  it("publishes OrderCreated v2 with channel API when configured", async () => {
    const res = await create(A, crypto.randomUUID(), appV2);
    expect(res.statusCode).toBe(202);
    const { orderId } = res.json<{ orderId: string }>();
    const out = await db.ownerPool.query<{
      message_id: string;
      correlation_id: string;
      type: string;
      version: number;
      occurred_at: Date;
      payload: Record<string, unknown>;
    }>(
      "SELECT message_id, correlation_id, type, version, occurred_at, payload FROM outbox WHERE payload->>'orderId' = $1",
      [orderId],
    );
    const row = out.rows[0];
    if (!row) throw new Error("OrderCreated not in outbox");
    expect(row.version).toBe(2);
    expect(row.payload).toEqual({ orderId, userId: A, ...body, channel: "API" });
    validator.validate(
      JSON.stringify({
        messageId: row.message_id,
        correlationId: row.correlation_id,
        type: row.type,
        version: row.version,
        occurredAt: row.occurred_at.toISOString(),
        payload: row.payload,
      }),
    );
  });

  // Review Focus 3: a client retry with the same key never creates a second order.
  it("same Idempotency-Key returns the same order with 200; another user gets its own", async () => {
    const key = crypto.randomUUID();
    const first = (await create(A, key)).json<{ orderId: string }>().orderId;
    const retry = await create(A, key);
    expect(retry.statusCode).toBe(200);
    expect(retry.json<{ orderId: string }>().orderId).toBe(first);
    const other = await create(B, key);
    expect(other.statusCode).toBe(202);
    expect(other.json<{ orderId: string }>().orderId).not.toBe(first);
    const n = await db.ownerPool.query(
      "SELECT 1 FROM orders WHERE idempotency_key = $1 AND user_id = $2",
      [key, A],
    );
    expect(n.rowCount).toBe(1);
  });

  // Review Focus 4: B never sees A's order, even through a query that forgets the user filter.
  it("returns 404 for another user's order and RLS hides rows from unfiltered queries", async () => {
    const orderId = (await create(A, crypto.randomUUID())).json<{ orderId: string }>().orderId;
    expect(
      (await app.inject({ method: "GET", url: `/orders/${orderId}`, headers: { "x-user-id": B } }))
        .statusCode,
    ).toBe(404);
    const mine = await app.inject({
      method: "GET",
      url: `/orders/${orderId}`,
      headers: { "x-user-id": A },
    });
    expect(mine.statusCode).toBe(200);
    expect(mine.json()).toMatchObject({ orderId, status: "PENDING", quantity: 2 });

    const client = await db.appPool.connect();
    try {
      await client.query("BEGIN");
      await client.query("SELECT set_config('app.user_id', $1, true)", [B]);
      const leaked = await client.query("SELECT id FROM orders WHERE id = $1", [orderId]);
      expect(leaked.rowCount).toBe(0);
      await client.query("ROLLBACK");
    } finally {
      client.release();
    }
  });

  it("lists only the caller's orders with cursor pagination", async () => {
    const user = crypto.randomUUID();
    for (let i = 0; i < 3; i++) await create(user, crypto.randomUUID());
    const first = await app.inject({
      method: "GET",
      url: "/orders?limit=2",
      headers: { "x-user-id": user },
    });
    const page1 = first.json<{ items: unknown[]; nextCursor: string | null }>();
    expect(page1.items).toHaveLength(2);
    expect(page1.nextCursor).not.toBeNull();
    const second = await app.inject({
      method: "GET",
      url: `/orders?limit=2&cursor=${page1.nextCursor ?? ""}`,
      headers: { "x-user-id": user },
    });
    const page2 = second.json<{ items: unknown[]; nextCursor: string | null }>();
    expect(page2.items).toHaveLength(1);
    expect(page2.nextCursor).toBeNull();
  });
});
