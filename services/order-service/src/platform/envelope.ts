import { v7 as uuidv7 } from "uuid";
import { z } from "zod";

/** A message that can never be processed; consumers send it to the DLQ (RFC 0003 §5.4). */
export class PoisonMessageError extends Error {
  override name = "PoisonMessageError";
}

/** The type/version pair has no schema or no handler. It is poison. */
export class UnsupportedVersionError extends PoisonMessageError {
  override name = "UnsupportedVersionError";
}

/** The message violates its schema. It is poison. */
export class InvalidMessageError extends PoisonMessageError {
  override name = "InvalidMessageError";
}

/** Thrown by a handler when the event no longer applies: recorded as processed and acknowledged. */
export class StaleEventError extends Error {
  override name = "StaleEventError";
}

/** The six-field message envelope (RFC 0001 §5.1). occurredAt is RFC 3339 UTC with milliseconds. */
export interface Envelope {
  messageId: string;
  correlationId: string;
  type: string;
  version: number;
  occurredAt: string;
  payload: Record<string, unknown>;
}

const envelopeSchema = z.object({
  messageId: z.string().min(1),
  correlationId: z.string(),
  type: z.string().min(1),
  version: z.number().int().min(1),
  occurredAt: z.string(),
  payload: z.record(z.string(), z.unknown()),
});

const snsNotification = z.object({ Type: z.literal("Notification"), Message: z.string() });

/** New envelope with a UUID v7 messageId and occurredAt = now. */
export function createEnvelope(
  correlationId: string,
  type: string,
  version: number,
  payload: Record<string, unknown>,
): Envelope {
  return {
    messageId: uuidv7(),
    correlationId,
    type,
    version,
    occurredAt: new Date().toISOString(),
    payload,
  };
}

/** Exactly the six envelope keys, in contract order. */
export function serializeEnvelope(env: Envelope): string {
  return JSON.stringify({
    messageId: env.messageId,
    correlationId: env.correlationId,
    type: env.type,
    version: env.version,
    occurredAt: env.occurredAt,
    payload: env.payload,
  });
}

function parseJson(text: string, what: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new PoisonMessageError(`${what} is not JSON`);
  }
}

/** The envelope JSON carried by an SQS body (SNS notification or raw delivery). Throws PoisonMessageError. */
export function rawEnvelope(body: string): string {
  const parsed = parseJson(body, "body");
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new PoisonMessageError("body is not a JSON object");
  }
  const sns = snsNotification.safeParse(parsed);
  return sns.success ? sns.data.Message : body;
}

/** Parses the envelope from an SQS body. Any failure throws PoisonMessageError. */
export function unwrap(body: string): Envelope {
  const result = envelopeSchema.safeParse(parseJson(rawEnvelope(body), "envelope"));
  if (!result.success) {
    throw new PoisonMessageError(`invalid envelope: ${result.error.message}`);
  }
  return result.data;
}
