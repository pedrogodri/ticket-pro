import type { Pool } from "pg";
import { v7 as uuidv7 } from "uuid";
import { createEnvelope } from "../../../platform/envelope.js";
import { enqueue } from "../../../platform/outbox.js";
import type { CreateOrderInput, OrderView } from "../domain/order.js";
import {
  findByIdempotencyKey,
  insertPending,
  withUser,
} from "../infrastructure/orderRepository.js";

const UNIQUE_VIOLATION = "23505";

/**
 * Creates a PENDING order and enqueues OrderCreated v1 in one transaction (arquitetura §6 step 1, R3).
 * The same Idempotency-Key from the same user returns the existing order (created = false).
 */
export async function createOrder(
  pool: Pool,
  userId: string,
  correlationId: string,
  input: CreateOrderInput,
  idempotencyKey: string,
  topicArn: string,
): Promise<{ order: OrderView; created: boolean }> {
  try {
    return await withUser(pool, userId, async (client) => {
      const existing = await findByIdempotencyKey(client, userId, idempotencyKey);
      if (existing) return { order: existing, created: false };
      const order = await insertPending(client, {
        id: uuidv7(),
        userId,
        key: idempotencyKey,
        ...input,
      });
      await enqueue(
        client,
        topicArn,
        createEnvelope(correlationId, "OrderCreated", 1, {
          orderId: order.orderId,
          userId,
          ...input,
        }),
      );
      return { order, created: true };
    });
  } catch (error) {
    // Two concurrent retries with the same key: the loser reads the winner's order.
    if ((error as { code?: string }).code === UNIQUE_VIOLATION) {
      const order = await withUser(pool, userId, (client) =>
        findByIdempotencyKey(client, userId, idempotencyKey),
      );
      if (order) return { order, created: false };
    }
    throw error;
  }
}
