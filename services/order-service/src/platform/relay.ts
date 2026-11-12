import type { Pool } from "pg";
import type { Envelope } from "./envelope.js";
import type { Logger } from "./logging.js";
import type { Publisher } from "./outbox.js";
import { delay, DEFAULT_BASE_MS } from "./retry.js";

/** Outbox relay tuning (OUTBOX_* environment variables, RFC 0003 §5.1). */
export interface RelayOptions {
  batchSize: number;
  pollIntervalMs: number;
  maxBackoffMs: number;
  /** Hides claimed rows from other relays while they are published outside a transaction. */
  claimLeaseMs: number;
}

interface Row {
  id: string;
  topicArn: string;
  traceparent: string | null;
  attempts: number;
  envelope: Envelope;
}

interface DbRow {
  id: string;
  message_id: string;
  topic_arn: string;
  type: string;
  version: number;
  correlation_id: string;
  traceparent: string | null;
  payload: Record<string, unknown>;
  occurred_at: Date;
  attempts: number;
}

const sleep = (ms: number, signal: AbortSignal): Promise<void> =>
  new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener(
      "abort",
      () => {
        clearTimeout(timer);
        resolve();
      },
      { once: true },
    );
  });

/** Publishes pending outbox rows (ADR-0005, RFC 0003 §5.3). Safe in every replica (SKIP LOCKED + lease). */
export class OutboxRelay {
  #brokerUp = true;

  constructor(
    private readonly pool: Pool,
    private readonly publisher: Publisher,
    private readonly opts: RelayOptions,
    private readonly logger: Logger,
  ) {}

  /** Whether the last publish attempt succeeded (readiness: broker up|degraded). */
  get brokerUp(): boolean {
    return this.#brokerUp;
  }

  /**
   * Claims one batch and publishes it in id order. On the first publish failure the row is rescheduled
   * with backoff, the rest of the batch is released, and the error is rethrown.
   */
  async runOnce(): Promise<number> {
    const rows = await this.claim();
    let published = 0;
    for (const [i, row] of rows.entries()) {
      try {
        await this.publisher.publish(row.topicArn, row.envelope, row.traceparent ?? undefined);
      } catch (error) {
        this.setBroker(false, error);
        await this.reschedule(row, error);
        await this.release(rows.slice(i + 1));
        throw error;
      }
      await this.pool.query(
        "UPDATE outbox SET published_at = now(), last_error = NULL WHERE id = $1",
        [row.id],
      );
      this.setBroker(true);
      published++;
      const e = row.envelope;
      this.logger.info(
        {
          messageId: e.messageId,
          type: e.type,
          version: e.version,
          correlationId: e.correlationId,
        },
        "message_published",
      );
    }
    return published;
  }

  /** Publishes until signal aborts: pollInterval between batches, backoff after failures. */
  async run(signal: AbortSignal): Promise<void> {
    let failures = 0;
    while (!signal.aborted) {
      let wait = this.opts.pollIntervalMs;
      try {
        const published = await this.runOnce();
        failures = 0;
        if (published === this.opts.batchSize) wait = 0; // full batch: keep draining
      } catch (error) {
        this.logger.warn({ error: String(error) }, "outbox_relay_error");
        wait = delay(failures++, DEFAULT_BASE_MS, this.opts.maxBackoffMs, Math.random);
      }
      await sleep(wait, signal);
    }
  }

  private async claim(): Promise<Row[]> {
    const result = await this.pool.query<DbRow>(
      `UPDATE outbox SET next_attempt_at = now() + ($2::bigint * interval '1 millisecond')
       WHERE id IN (
         SELECT id FROM outbox
         WHERE published_at IS NULL AND next_attempt_at <= now()
         ORDER BY id LIMIT $1
         FOR UPDATE SKIP LOCKED)
       RETURNING id, message_id, topic_arn, type, version, correlation_id, traceparent, payload, occurred_at, attempts`,
      [this.opts.batchSize, this.opts.claimLeaseMs],
    );
    return result.rows
      .map((r) => ({
        id: r.id,
        topicArn: r.topic_arn,
        traceparent: r.traceparent,
        attempts: r.attempts,
        envelope: {
          messageId: r.message_id,
          correlationId: r.correlation_id,
          type: r.type,
          version: r.version,
          occurredAt: r.occurred_at.toISOString(),
          payload: r.payload,
        },
      }))
      .sort((a, b) => Number(BigInt(a.id) - BigInt(b.id))); // RETURNING has no order
  }

  private async reschedule(row: Row, cause: unknown): Promise<void> {
    const ms = delay(row.attempts + 1, DEFAULT_BASE_MS, this.opts.maxBackoffMs, Math.random);
    await this.pool.query(
      `UPDATE outbox SET attempts = attempts + 1, last_error = $2,
              next_attempt_at = now() + ($3::bigint * interval '1 millisecond')
       WHERE id = $1`,
      [row.id, String(cause), Math.round(ms)],
    );
  }

  private async release(rows: Row[]): Promise<void> {
    if (rows.length === 0) return;
    await this.pool.query(
      "UPDATE outbox SET next_attempt_at = now() WHERE id = ANY($1::bigint[])",
      [rows.map((r) => r.id)],
    );
  }

  // One log line per broker state transition (RFC 0003 §5.3).
  private setBroker(up: boolean, cause?: unknown): void {
    if (this.#brokerUp === up) return;
    this.#brokerUp = up;
    if (up) this.logger.info("broker_up");
    else this.logger.warn({ error: String(cause) }, "broker_down");
  }
}
