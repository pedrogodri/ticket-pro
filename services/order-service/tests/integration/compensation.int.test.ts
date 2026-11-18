import type { PoolClient } from "pg";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createDeadlineScanner } from "../../src/modules/orders/application/deadlineScanner.js";
import { createSagaHandlers } from "../../src/modules/orders/application/saga.js";
import type { MessageHandler } from "../../src/platform/consumer.js";
import { StaleEventError, createEnvelope, type Envelope } from "../../src/platform/envelope.js";
import { createValidator, type ContractValidator } from "../../src/platform/validator.js";
import { startDatabase, type TestDatabase } from "../support/database.js";

const TOPIC = "arn:aws:sns:us-east-1:000000000000:ticketpro-orders";
const USER = "0193a6f0-1111-7000-8000-000000000001";
const EVENT = "0193a6f0-3333-7000-8000-000000000003";
const SECTOR = "0193a6f0-4444-7000-8000-000000000004";
const PAYMENT = "0193a6f0-6666-7000-8000-000000000006";

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

/** status and deadline offset in seconds from now (negative = already past). */
async function newOrder(status: string, deadlineInS: number | null = null): Promise<string> {
  const id = crypto.randomUUID();
  await db.ownerPool.query(
    `INSERT INTO orders (id, user_id, idempotency_key, event_id, sector_id, quantity, status, total_cents, currency, payment_deadline_at)
     VALUES ($1, $2, $3, $4, $5, 2, $6, 50000, 'BRL',
       CASE WHEN $7::int IS NULL THEN NULL ELSE now() + ($7::int * interval '1 second') END)`,
    [id, USER, crypto.randomUUID(), EVENT, SECTOR, status, deadlineInS],
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
    refund_status: string | null;
  }>("SELECT status, cancel_reason, refund_status FROM orders WHERE id = $1", [id]);
  return r.rows[0];
}

/** Outbox messages of the order by type (count), each validated against its contract. */
async function produced(orderId: string): Promise<Map<string, Record<string, unknown>[]>> {
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
  const out = new Map<string, Record<string, unknown>[]>();
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
    out.set(row.type, [...(out.get(row.type) ?? []), row.payload]);
  }
  return out;
}

const approved = (orderId: string) => ({
  orderId,
  paymentId: PAYMENT,
  amountCents: 50000,
  currency: "BRL",
  providerReference: "SIM-1",
  approvedAt: "2026-11-20T18:30:13.900Z",
});

describe("F3 compensations (RFC 0003 §5.7)", () => {
  it("InventoryReservationExpired cancels a pending or awaiting order with RESERVATION_EXPIRED", async () => {
    for (const status of ["PENDING", "AWAITING_PAYMENT"]) {
      const id = await newOrder(status, status === "PENDING" ? null : 60);
      expect(
        await handle("InventoryReservationExpired", {
          orderId: id,
          reservationId: crypto.randomUUID(),
          quantity: 2,
        }),
      ).toBe("ok");
      expect(await orderOf(id)).toMatchObject({
        status: "CANCELLED",
        cancel_reason: "RESERVATION_EXPIRED",
      });
      expect((await produced(id)).get("OrderCancelled")?.[0]).toMatchObject({
        reason: "RESERVATION_EXPIRED",
      });
    }
  });

  // Review Focus 3
  it("InventoryReservationExpired for a confirmed order is stale", async () => {
    const id = await newOrder("CONFIRMED", 60);
    expect(
      await handle("InventoryReservationExpired", {
        orderId: id,
        reservationId: crypto.randomUUID(),
        quantity: 2,
      }),
    ).toBe("stale");
    expect((await orderOf(id))?.status).toBe("CONFIRMED");
  });

  it("PaymentApproved past the deadline cancels with PAYMENT_TIMEOUT and requests the refund in one TX", async () => {
    const id = await newOrder("AWAITING_PAYMENT", -1);
    expect(await handle("PaymentApproved", approved(id))).toBe("ok");
    expect(await orderOf(id)).toMatchObject({
      status: "CANCELLED",
      cancel_reason: "PAYMENT_TIMEOUT",
      refund_status: "REQUESTED",
    });
    const out = await produced(id);
    expect(out.get("OrderCancelled")?.[0]).toMatchObject({ reason: "PAYMENT_TIMEOUT" });
    expect(out.get("RefundRequested")?.[0]).toMatchObject({
      paymentId: PAYMENT,
      amountCents: 50000,
      reason: "LATE_APPROVAL",
    });
  });

  // Review Focus 2
  it("a late PaymentApproved for a cancelled order requests one refund, even when repeated", async () => {
    const id = await newOrder("CANCELLED");
    await db.ownerPool.query("UPDATE orders SET cancel_reason = 'PAYMENT_TIMEOUT' WHERE id = $1", [
      id,
    ]);
    expect(await handle("PaymentApproved", approved(id))).toBe("ok");
    expect(await handle("PaymentApproved", approved(id))).toBe("stale");
    expect((await produced(id)).get("RefundRequested")).toHaveLength(1);
    expect((await orderOf(id))?.refund_status).toBe("REQUESTED");
  });

  it("PaymentRefunded marks the order REFUNDED once", async () => {
    const id = await newOrder("CANCELLED");
    await db.ownerPool.query(
      "UPDATE orders SET cancel_reason = 'PAYMENT_TIMEOUT', refund_status = 'REQUESTED' WHERE id = $1",
      [id],
    );
    const refunded = {
      orderId: id,
      paymentId: PAYMENT,
      refundId: crypto.randomUUID(),
      amountCents: 50000,
      currency: "BRL",
    };
    expect(await handle("PaymentRefunded", refunded)).toBe("ok");
    expect(await handle("PaymentRefunded", refunded)).toBe("stale");
    expect((await orderOf(id))?.refund_status).toBe("REFUNDED");
  });

  // Review Focus 1 and 5
  it("the deadline scanner cancels overdue orders once, even with two scanners at the same time", async () => {
    const overdue = await Promise.all([1, 2, 3, 4, 5].map(() => newOrder("AWAITING_PAYMENT", -1)));
    const fresh = await newOrder("AWAITING_PAYMENT", 60);
    const a = createDeadlineScanner(db.workerPool, {
      topicArn: TOPIC,
      intervalMs: 1000,
      batchSize: 2,
    });
    const b = createDeadlineScanner(db.workerPool, {
      topicArn: TOPIC,
      intervalMs: 1000,
      batchSize: 2,
    });
    const drain = async (s: typeof a) => {
      while ((await s.runOnce()) > 0) {
        /* keep draining */
      }
    };
    await Promise.all([drain(a), drain(b)]);
    for (const id of overdue) {
      expect(await orderOf(id)).toMatchObject({
        status: "CANCELLED",
        cancel_reason: "PAYMENT_TIMEOUT",
      });
      expect((await produced(id)).get("OrderCancelled")).toHaveLength(1);
    }
    expect((await orderOf(fresh))?.status).toBe("AWAITING_PAYMENT");
    // A PaymentApproved after the scanner cancelled becomes a refund.
    expect(await handle("PaymentApproved", approved(overdue[0] as string))).toBe("ok");
    expect((await produced(overdue[0] as string)).get("RefundRequested")).toHaveLength(1);
  });
});
