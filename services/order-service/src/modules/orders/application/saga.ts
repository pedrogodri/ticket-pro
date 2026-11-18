import type { PoolClient } from "pg";
import { z } from "zod";
import type { MessageHandler } from "../../../platform/consumer.js";
import {
  createEnvelope,
  InvalidMessageError,
  StaleEventError,
  type Envelope,
} from "../../../platform/envelope.js";
import { enqueue } from "../../../platform/outbox.js";
import type { CancelReason } from "../domain/order.js";

/**
 * Saga orchestration (arquitetura §6). Every transition is UPDATE … WHERE status = <expected>; no row
 * means the event is late, repeated or out of order → StaleEventError (recorded, acked, no effect).
 * Handlers run on the worker role (BYPASSRLS), inside the consumer's transaction.
 */
export interface SagaOptions {
  topicArn: string; // ORDERS_TOPIC_ARN
  paymentTimeoutMs: number; // ORDER_PAYMENT_TIMEOUT_MS
}

interface OrderRow {
  id: string;
  user_id: string;
  event_id: string;
  sector_id: string;
  quantity: number;
  total_cents: string;
  currency: string;
}

const reservedPayload = z.object({
  orderId: z.uuid(),
  totalCents: z.number().int(),
  currency: z.string(),
});
const orderIdPayload = z.object({ orderId: z.uuid() });
const approvedPayload = z.object({
  orderId: z.uuid(),
  paymentId: z.uuid(),
  amountCents: z.number().int(),
  currency: z.string(),
});

function parse<T>(schema: z.ZodType<T>, env: Envelope): T {
  const result = schema.safeParse(env.payload);
  if (!result.success) throw new InvalidMessageError(`${env.type}: ${result.error.message}`);
  return result.data;
}

/** One transition: the UPDATE must return the order, otherwise the event is stale. */
async function transition(client: PoolClient, sql: string, params: unknown[]): Promise<OrderRow> {
  const r = await client.query<OrderRow>(
    `${sql} RETURNING id, user_id, event_id, sector_id, quantity, total_cents, currency`,
    params,
  );
  const row = r.rows[0];
  if (!row) throw new StaleEventError("order is not in the expected state");
  return row;
}

async function cancel(
  client: PoolClient,
  options: SagaOptions,
  env: Envelope,
  fromStatus: readonly ("PENDING" | "AWAITING_PAYMENT")[],
  reason: CancelReason,
): Promise<void> {
  const { orderId } = parse(orderIdPayload, env);
  const order = await transition(
    client,
    `UPDATE orders SET status = 'CANCELLED', cancel_reason = $2, updated_at = now() WHERE id = $1 AND status = ANY($3)`,
    [orderId, reason, fromStatus],
  );
  await enqueueCancelled(client, options, env.correlationId, order, reason);
}

async function enqueueCancelled(
  client: PoolClient,
  options: SagaOptions,
  correlationId: string,
  order: OrderRow,
  reason: CancelReason,
): Promise<void> {
  await enqueue(
    client,
    options.topicArn,
    createEnvelope(correlationId, "OrderCancelled", 1, {
      orderId: order.id,
      userId: order.user_id,
      reason,
      cancelledAt: new Date().toISOString(),
    }),
  );
}

/**
 * PaymentApproved that cannot confirm (RFC 0003 §5.7, F7). Still AWAITING_PAYMENT but past the deadline
 * → CANCELLED(PAYMENT_TIMEOUT) + OrderCancelled + RefundRequested in this transaction; already CANCELLED
 * without a refund → RefundRequested. refund_status IS NULL keeps a repeated approval to one refund.
 */
async function refundLateApproval(
  client: PoolClient,
  options: SagaOptions,
  env: Envelope,
  p: z.infer<typeof approvedPayload>,
): Promise<void> {
  const timedOut = await client.query<OrderRow>(
    `UPDATE orders SET status = 'CANCELLED', cancel_reason = 'PAYMENT_TIMEOUT', refund_status = 'REQUESTED', updated_at = now()
     WHERE id = $1 AND status = 'AWAITING_PAYMENT' AND payment_deadline_at < now()
     RETURNING id, user_id, event_id, sector_id, quantity, total_cents, currency`,
    [p.orderId],
  );
  const cancelledNow = timedOut.rows[0];
  if (cancelledNow) {
    await enqueueCancelled(client, options, env.correlationId, cancelledNow, "PAYMENT_TIMEOUT");
  } else {
    await transition(
      client,
      `UPDATE orders SET refund_status = 'REQUESTED', updated_at = now()
       WHERE id = $1 AND status = 'CANCELLED' AND refund_status IS NULL`,
      [p.orderId],
    );
  }
  await enqueue(
    client,
    options.topicArn,
    createEnvelope(env.correlationId, "RefundRequested", 1, {
      orderId: p.orderId,
      paymentId: p.paymentId,
      amountCents: p.amountCents,
      currency: p.currency,
      reason: "LATE_APPROVAL",
    }),
  );
}

const v1: ReadonlySet<number> = new Set([1]);

export function createSagaHandlers(options: SagaOptions): ReadonlyMap<string, MessageHandler> {
  return new Map<string, MessageHandler>([
    [
      "InventoryReserved",
      {
        versions: v1,
        // Step 3: AWAITING_PAYMENT with the price from the inventory and a payment deadline.
        handle: async (env, client) => {
          const p = parse(reservedPayload, env);
          const order = await transition(
            client,
            `UPDATE orders SET status = 'AWAITING_PAYMENT', total_cents = $2, currency = $3,
               payment_deadline_at = now() + ($4::bigint * interval '1 millisecond'), updated_at = now()
             WHERE id = $1 AND status = 'PENDING'`,
            [p.orderId, p.totalCents, p.currency, options.paymentTimeoutMs],
          );
          await enqueue(
            client,
            options.topicArn,
            createEnvelope(env.correlationId, "PaymentRequested", 1, {
              orderId: order.id,
              userId: order.user_id,
              amountCents: Number(order.total_cents),
              currency: order.currency,
            }),
          );
        },
      },
    ],
    [
      "InventoryRejected",
      {
        versions: v1,
        handle: (env, client) => cancel(client, options, env, ["PENDING"], "OUT_OF_STOCK"),
      },
    ],
    [
      "PaymentApproved",
      {
        versions: v1,
        // Step 5: confirm only while AWAITING_PAYMENT and within the deadline; otherwise refund.
        handle: async (env, client) => {
          const p = parse(approvedPayload, env);
          const confirmed = await client.query<OrderRow>(
            `UPDATE orders SET status = 'CONFIRMED', updated_at = now()
             WHERE id = $1 AND status = 'AWAITING_PAYMENT' AND payment_deadline_at >= now()
             RETURNING id, user_id, event_id, sector_id, quantity, total_cents, currency`,
            [p.orderId],
          );
          const order = confirmed.rows[0];
          if (!order) {
            await refundLateApproval(client, options, env, p);
            return;
          }
          await enqueue(
            client,
            options.topicArn,
            createEnvelope(env.correlationId, "OrderConfirmed", 1, {
              orderId: order.id,
              userId: order.user_id,
              eventId: order.event_id,
              sectorId: order.sector_id,
              quantity: order.quantity,
              totalCents: Number(order.total_cents),
              currency: order.currency,
              confirmedAt: new Date().toISOString(),
            }),
          );
        },
      },
    ],
    [
      "PaymentFailed",
      {
        versions: v1,
        handle: (env, client) =>
          cancel(client, options, env, ["AWAITING_PAYMENT"], "PAYMENT_FAILED"),
      },
    ],
    [
      "InventoryReservationExpired",
      {
        versions: v1,
        // Safety net (arquitetura §6 step 9): the inventory already released the stock.
        handle: (env, client) =>
          cancel(client, options, env, ["PENDING", "AWAITING_PAYMENT"], "RESERVATION_EXPIRED"),
      },
    ],
    [
      "PaymentRefunded",
      {
        versions: v1,
        handle: async (env, client) => {
          const { orderId } = parse(orderIdPayload, env);
          await transition(
            client,
            `UPDATE orders SET refund_status = 'REFUNDED', updated_at = now()
             WHERE id = $1 AND refund_status = 'REQUESTED'`,
            [orderId],
          );
        },
      },
    ],
  ]);
}
