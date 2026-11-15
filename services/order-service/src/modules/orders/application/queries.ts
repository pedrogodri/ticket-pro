import type { Pool } from "pg";
import type { OrderView } from "../domain/order.js";
import { findById, listByUser, withUser } from "../infrastructure/orderRepository.js";

/** The caller's order, or null when it does not exist or belongs to someone else (BOLA → 404). */
export function getOrder(pool: Pool, userId: string, orderId: string): Promise<OrderView | null> {
  return withUser(pool, userId, (client) => findById(client, userId, orderId));
}

/** Cursor = base64url("createdAt|id") of the last item; newest first. */
export async function listOrders(
  pool: Pool,
  userId: string,
  limit: number,
  cursor: string | undefined,
): Promise<{ items: OrderView[]; nextCursor: string | null }> {
  const after = cursor ? decodeCursor(cursor) : null;
  const rows = await withUser(pool, userId, (client) =>
    listByUser(client, userId, limit + 1, after),
  );
  const items = rows.slice(0, limit);
  const last = items.at(-1);
  return { items, nextCursor: rows.length > limit && last ? encodeCursor(last) : null };
}

export class InvalidCursorError extends Error {}

function encodeCursor(o: OrderView): string {
  return Buffer.from(`${o.createdAt}|${o.orderId}`).toString("base64url");
}

function decodeCursor(cursor: string): { createdAt: string; id: string } {
  const [createdAt, id] = Buffer.from(cursor, "base64url").toString("utf8").split("|");
  if (!createdAt || !id || Number.isNaN(Date.parse(createdAt)))
    throw new InvalidCursorError("invalid cursor");
  return { createdAt, id };
}
