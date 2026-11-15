import { z } from "zod";

/** POST /orders body: strict (unknown fields, including userId → 400). */
export const createOrderBody = (maxQuantity: number) =>
  z.strictObject({
    eventId: z.uuid(),
    sectorId: z.uuid(),
    quantity: z.number().int().min(1).max(maxQuantity),
  });

export const idempotencyKey = z.uuid();
export const orderId = z.uuid();
export const listQuery = z.strictObject({
  limit: z.coerce.number().int().min(1).max(50).default(20),
  cursor: z.string().optional(),
});
