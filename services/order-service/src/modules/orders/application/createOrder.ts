import type { Pool } from "pg";
import type { OrderCreatedVersion } from "../../../app/config.js";
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
 * Creates a PENDING order and enqueues OrderCreated in one transaction (arquitetura §6 step 1, R3).
 * The version follows ORDER_CREATED_VERSION; v2 adds channel, always "API" because orders only enter
 * through the HTTP API (RFC 0001 §5.5).
 * The same Idempotency-Key from the same user returns the existing order (created = false).
 */
export async function createOrder(
  pool: Pool,
  userId: string,
  correlationId: string,
  input: CreateOrderInput,
  idempotencyKey: string,
  topicArn: string,
  version: OrderCreatedVersion,
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
        createEnvelope(correlationId, "OrderCreated", version, {
          orderId: order.orderId,
          userId,
          ...input,
          ...(version === 2 ? { channel: "API" } : {}),
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
