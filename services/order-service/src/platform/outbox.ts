import type { PoolClient } from "pg";
import type { Envelope } from "./envelope.js";
import { inject } from "./tracing.js";

/** Sends one envelope to a topic; traceparent is undefined when the event has no trace context. */
export interface Publisher {
  publish(topicArn: string, env: Envelope, traceparent: string | undefined): Promise<void>;
}

/** Inserts env into the outbox in the caller's transaction, with the active span's traceparent (ADR-0005). */
export async function enqueue(client: PoolClient, topicArn: string, env: Envelope): Promise<void> {
  await client.query(
    `INSERT INTO outbox (message_id, topic_arn, type, version, correlation_id, traceparent, payload, occurred_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
    [
      env.messageId,
      topicArn,
      env.type,
      env.version,
      env.correlationId,
      inject() ?? null,
      JSON.stringify(env.payload),
      env.occurredAt,
    ],
  );
}
