import type { PoolClient } from "pg";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createSagaHandlers } from "../../src/modules/orders/application/saga.js";
import { StaleEventError, createEnvelope, type Envelope } from "../../src/platform/envelope.js";
import type { MessageHandler } from "../../src/platform/consumer.js";
import { createValidator, type ContractValidator } from "../../src/platform/validator.js";
import { startDatabase, type TestDatabase } from "../support/database.js";

const TOPIC = "arn:aws:sns:us-east-1:000000000000:ticketpro-orders";
const USER = "0193a6f0-1111-7000-8000-000000000001";
const EVENT = "0193a6f0-3333-7000-8000-000000000003";
const SECTOR = "0193a6f0-4444-7000-8000-000000000004";

let db: TestDatabase;
let validator: ContractValidator;
let handlers: ReadonlyMap<string, MessageHandler>;

beforeAll(async () => {
  db = await startDatabase();
  validator = await createValidator("contracts");
  handlers = createSagaHandlers({ topicArn: TOPIC, paymentTimeoutMs: 15_000 });
}, 120_000);

afterAll(async () => {
  await db.stop();
});

async function newOrder(status: string, deadline: string | null = null): Promise<string> {
  const id = crypto.randomUUID();
  await db.ownerPool.query(
    `INSERT INTO orders (id, user_id, idempotency_key, event_id, sector_id, quantity, status, total_cents, currency, payment_deadline_at)
     VALUES ($1, $2, $3, $4, $5, 2, $6, CASE WHEN $6 = 'PENDING' THEN NULL ELSE 50000 END, CASE WHEN $6 = 'PENDING' THEN NULL ELSE 'BRL' END, $7)`,
    [id, USER, crypto.randomUUID(), EVENT, SECTOR, status, deadline],
  );
  return id;
}

async function handle(type: string, payload: Record<string, unknown>): Promise<"ok" | "stale"> {
  const handler = handlers.get(type);
  if (!handler) throw new Error(`no handler for ${type}`);
  const env: Envelope = createEnvelope("c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c", type, 1, payload);
  const client: PoolClient = await db.workerPool.connect();
  try {
    await client.query("BEGIN");
    await handler.handle(env, client);
    await client.query("COMMIT");
    return "ok";
  } catch (error) {
    await client.query("ROLLBACK");
    if (error instanceof StaleEventError) return "stale";
    throw error;
  } finally {
    client.release();
  }
}

async function orderOf(id: string) {
  const r = await db.ownerPool.query<{
    status: string;
    cancel_reason: string | null;
    total_cents: string | null;
    payment_deadline_at: Date | null;
  }>("SELECT status, cancel_reason, total_cents, payment_deadline_at FROM orders WHERE id = $1", [
    id,
  ]);
  return r.rows[0];
}

/** Outbox messages for the order, each validated against its contract. */
async function produced(orderId: string): Promise<Map<string, Record<string, unknown>>> {
  const r = await db.ownerPool.query<{
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
  const out = new Map<string, Record<string, unknown>>();
  for (const row of r.rows) {
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
    out.set(row.type, row.payload);
  }
  return out;
}

const reserved = (orderId: string) => ({
  orderId,
  reservationId: crypto.randomUUID(),
  eventId: EVENT,
  sectorId: SECTOR,
  quantity: 2,
  unitPriceCents: 25000,
  totalCents: 50000,
  currency: "BRL",
  expiresAt: "2026-11-20T18:31:12.500Z",
});
const approved = (orderId: string) => ({
  orderId,
  paymentId: crypto.randomUUID(),
  amountCents: 50000,
  currency: "BRL",
  providerReference: "SIM-1",
  approvedAt: "2026-11-20T18:30:13.900Z",
});

describe("purchase saga (arquitetura §6)", () => {
  it("InventoryReserved → AWAITING_PAYMENT with deadline and PaymentRequested", async () => {
    const id = await newOrder("PENDING");
    expect(await handle("InventoryReserved", reserved(id))).toBe("ok");
    const o = await orderOf(id);
    expect(o?.status).toBe("AWAITING_PAYMENT");
    expect(Number(o?.total_cents)).toBe(50000);
    expect(o?.payment_deadline_at).not.toBeNull();
    expect((await produced(id)).get("PaymentRequested")).toEqual({
      orderId: id,
      userId: USER,
      amountCents: 50000,
      currency: "BRL",
    });
  });

  it("InventoryRejected → CANCELLED(OUT_OF_STOCK) and OrderCancelled", async () => {
    const id = await newOrder("PENDING");
    expect(
      await handle("InventoryRejected", {
        orderId: id,
        eventId: EVENT,
        sectorId: SECTOR,
        quantity: 2,
        reason: "OUT_OF_STOCK",
      }),
    ).toBe("ok");
    expect(await orderOf(id)).toMatchObject({ status: "CANCELLED", cancel_reason: "OUT_OF_STOCK" });
    expect((await produced(id)).get("OrderCancelled")).toMatchObject({
      orderId: id,
      userId: USER,
      reason: "OUT_OF_STOCK",
    });
  });

  it("PaymentApproved within the deadline → CONFIRMED and OrderConfirmed", async () => {
    const id = await newOrder("AWAITING_PAYMENT", new Date(Date.now() + 60_000).toISOString());
    expect(await handle("PaymentApproved", approved(id))).toBe("ok");
    expect((await orderOf(id))?.status).toBe("CONFIRMED");
    expect((await produced(id)).get("OrderConfirmed")).toMatchObject({
      orderId: id,
      userId: USER,
      quantity: 2,
      totalCents: 50000,
      currency: "BRL",
    });
  });

  it("PaymentFailed → CANCELLED(PAYMENT_FAILED) and OrderCancelled", async () => {
    const id = await newOrder("AWAITING_PAYMENT", new Date(Date.now() + 60_000).toISOString());
    expect(
      await handle("PaymentFailed", {
        orderId: id,
        paymentId: crypto.randomUUID(),
        reason: "DECLINED",
      }),
    ).toBe("ok");
    expect(await orderOf(id)).toMatchObject({
      status: "CANCELLED",
      cancel_reason: "PAYMENT_FAILED",
    });
    expect((await produced(id)).get("OrderCancelled")).toMatchObject({ reason: "PAYMENT_FAILED" });
  });

  it("out-of-order, repeated or late events are stale and never regress the state", async () => {
    const pending = await newOrder("PENDING");
    expect(await handle("PaymentApproved", approved(pending))).toBe("stale");
    expect((await orderOf(pending))?.status).toBe("PENDING");

    // F3 (RFC 0003 §5.7): a late approval no longer stays AWAITING_PAYMENT, it cancels and refunds.
    const late = await newOrder("AWAITING_PAYMENT", new Date(Date.now() - 1000).toISOString());
    expect(await handle("PaymentApproved", approved(late))).toBe("ok");
    expect(await orderOf(late)).toMatchObject({
      status: "CANCELLED",
      cancel_reason: "PAYMENT_TIMEOUT",
    });

    const id = await newOrder("PENDING");
    expect(await handle("InventoryReserved", reserved(id))).toBe("ok");
    expect(await handle("InventoryReserved", reserved(id))).toBe("stale");
  });
});
