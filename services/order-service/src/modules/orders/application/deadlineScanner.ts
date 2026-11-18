import type { Pool } from "pg";
import { v7 as uuidv7 } from "uuid";
import { createEnvelope } from "../../../platform/envelope.js";
import type { Logger } from "../../../platform/logging.js";
import { enqueue } from "../../../platform/outbox.js";

export interface DeadlineScannerOptions {
  topicArn: string; // ORDERS_TOPIC_ARN
  intervalMs: number; // ORDER_DEADLINE_SCAN_INTERVAL_MS
  batchSize: number; // ORDER_DEADLINE_SCAN_BATCH_SIZE
}

export interface DeadlineScanner {
  /** Cancels up to batchSize overdue orders in one transaction; returns how many. */
  runOnce(): Promise<number>;
  /** Scans every intervalMs until signal aborts. A failed scan is logged and retried next tick. */
  run(signal: AbortSignal, logger: Logger): Promise<void>;
}

/**
 * Deadline scanner (arquitetura §6 step 7, RFC 0003 §5.7): AWAITING_PAYMENT past payment_deadline_at →
 * CANCELLED(PAYMENT_TIMEOUT) + OrderCancelled, so the inventory releases the stock. FOR UPDATE SKIP LOCKED
 * makes it safe in every replica; a PaymentApproved racing it either confirms first (the scanner skips
 * the row) or arrives after and becomes a refund.
 */
export function createDeadlineScanner(
  pool: Pool,
  options: DeadlineScannerOptions,
): DeadlineScanner {
  const runOnce = async (): Promise<number> => {
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      const r = await client.query<{ id: string; user_id: string }>(
        `WITH due AS (
           SELECT id FROM orders WHERE status = 'AWAITING_PAYMENT' AND payment_deadline_at < now()
           ORDER BY payment_deadline_at LIMIT $1 FOR UPDATE SKIP LOCKED)
         UPDATE orders o SET status = 'CANCELLED', cancel_reason = 'PAYMENT_TIMEOUT', updated_at = now()
         FROM due WHERE o.id = due.id
         RETURNING o.id, o.user_id`,
        [options.batchSize],
      );
      for (const row of r.rows) {
        // orders keeps no correlationId; consumers correlate the saga by orderId.
        await enqueue(
          client,
          options.topicArn,
          createEnvelope(uuidv7(), "OrderCancelled", 1, {
            orderId: row.id,
            userId: row.user_id,
            reason: "PAYMENT_TIMEOUT",
            cancelledAt: new Date().toISOString(),
          }),
        );
      }
      await client.query("COMMIT");
      return r.rows.length;
    } catch (error) {
      await client.query("ROLLBACK").catch(() => undefined);
      throw error;
    } finally {
      client.release();
    }
  };

  const run = async (signal: AbortSignal, logger: Logger): Promise<void> => {
    while (!signal.aborted) {
      try {
        const n = await runOnce();
        if (n > 0) logger.info({ count: n }, "orders_payment_timeout");
        if (n === options.batchSize) continue; // backlog: keep draining
      } catch (error) {
        logger.warn({ error: (error as Error).message }, "deadline_scan_failed");
      }
      await new Promise<void>((resolve) => {
        const t = setTimeout(resolve, options.intervalMs);
        signal.addEventListener(
          "abort",
          () => {
            clearTimeout(t);
            resolve();
          },
          { once: true },
        );
      });
    }
  };

  return { runOnce, run };
}
