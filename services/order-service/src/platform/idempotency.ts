import type { PoolClient } from "pg";

/** Inserts (consumer, messageId) in the caller's transaction; false means a duplicate (ADR-0006). */
export async function recordProcessed(
  client: PoolClient,
  consumer: string,
  messageId: string,
): Promise<boolean> {
  const result = await client.query(
    "INSERT INTO processed_messages (consumer, message_id) VALUES ($1, $2) ON CONFLICT DO NOTHING",
    [consumer, messageId],
  );
  return result.rowCount === 1;
}
