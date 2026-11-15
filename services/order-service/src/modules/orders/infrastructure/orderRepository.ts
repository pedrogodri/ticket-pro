import type { Pool, PoolClient } from "pg";
import type { CancelReason, OrderStatus, OrderView } from "../domain/order.js";

interface OrderRow {
  id: string;
  status: OrderStatus;
  event_id: string;
  sector_id: string;
  quantity: number;
  total_cents: string | null;
  currency: string | null;
  cancel_reason: CancelReason | null;
  created_at: Date;
}

const COLUMNS =
  "id, status, event_id, sector_id, quantity, total_cents, currency, cancel_reason, created_at";

export function toView(r: OrderRow): OrderView {
  return {
    orderId: r.id,
    status: r.status,
    eventId: r.event_id,
    sectorId: r.sector_id,
    quantity: r.quantity,
    totalCents: r.total_cents === null ? null : Number(r.total_cents),
    currency: r.currency,
    cancelReason: r.cancel_reason,
    createdAt: r.created_at.toISOString(),
  };
}

/**
 * Runs fn in a transaction scoped to userId: SET LOCAL app.user_id makes RLS filter every query
 * (padroes §6.1), on top of the explicit user_id filters.
 */
export async function withUser<T>(
  pool: Pool,
  userId: string,
  fn: (client: PoolClient) => Promise<T>,
): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    await client.query("SELECT set_config('app.user_id', $1, true)", [userId]);
    const result = await fn(client);
    await client.query("COMMIT");
    return result;
  } catch (error) {
    await client.query("ROLLBACK").catch(() => undefined);
    throw error;
  } finally {
    client.release();
  }
}

export async function findByIdempotencyKey(
  client: PoolClient,
  userId: string,
  key: string,
): Promise<OrderView | null> {
  const r = await client.query<OrderRow>(
    `SELECT ${COLUMNS} FROM orders WHERE user_id = $1 AND idempotency_key = $2`,
    [userId, key],
  );
  return r.rows[0] ? toView(r.rows[0]) : null;
}

export async function insertPending(
  client: PoolClient,
  order: {
    id: string;
    userId: string;
    key: string;
    eventId: string;
    sectorId: string;
    quantity: number;
  },
): Promise<OrderView> {
  const r = await client.query<OrderRow>(
    `INSERT INTO orders (id, user_id, idempotency_key, event_id, sector_id, quantity, status)
     VALUES ($1, $2, $3, $4, $5, $6, 'PENDING') RETURNING ${COLUMNS}`,
    [order.id, order.userId, order.key, order.eventId, order.sectorId, order.quantity],
  );
  const row = r.rows[0];
  if (!row) throw new Error("insert returned no row");
  return toView(row);
}

export async function findById(
  client: PoolClient,
  userId: string,
  id: string,
): Promise<OrderView | null> {
  const r = await client.query<OrderRow>(
    `SELECT ${COLUMNS} FROM orders WHERE id = $1 AND user_id = $2`,
    [id, userId],
  );
  return r.rows[0] ? toView(r.rows[0]) : null;
}

export async function listByUser(
  client: PoolClient,
  userId: string,
  limit: number,
  after: { createdAt: string; id: string } | null,
): Promise<OrderView[]> {
  const r = after
    ? await client.query<OrderRow>(
        `SELECT ${COLUMNS} FROM orders WHERE user_id = $1 AND (created_at, id) < ($3::timestamptz, $4::uuid)
         ORDER BY created_at DESC, id DESC LIMIT $2`,
        [userId, limit, after.createdAt, after.id],
      )
    : await client.query<OrderRow>(
        `SELECT ${COLUMNS} FROM orders WHERE user_id = $1 ORDER BY created_at DESC, id DESC LIMIT $2`,
        [userId, limit],
      );
  return r.rows.map(toView);
}
