import { PublishCommand, type MessageAttributeValue, type SNSClient } from "@aws-sdk/client-sns";
import { NodeHttpHandler } from "@smithy/node-http-handler";
import { serializeEnvelope, type Envelope } from "./envelope.js";
import type { Publisher } from "./outbox.js";

/** AWS client options shared by every client: no SDK retries (our backoff owns retrying) and a timeout. */
export function awsClientConfig(timeoutMs: number) {
  return {
    maxAttempts: 1,
    // throwOnRequestTimeout: without it smithy only logs a warning and the call hangs on a paused broker.
    requestHandler: new NodeHttpHandler({
      connectionTimeout: timeoutMs,
      requestTimeout: timeoutMs,
      throwOnRequestTimeout: true,
    }),
  };
}

const attr = (value: string): MessageAttributeValue => ({ DataType: "String", StringValue: value });

/** Publishes envelopes as the SNS Message with type, version, correlationId and traceparent attributes. */
export class SnsPublisher implements Publisher {
  constructor(private readonly sns: SNSClient) {}

  /** An empty traceparent is omitted: SNS rejects empty String attributes. */
  async publish(topicArn: string, env: Envelope, traceparent: string | undefined): Promise<void> {
    const attributes: Record<string, MessageAttributeValue> = {
      type: attr(env.type),
      version: attr(String(env.version)),
      correlationId: attr(env.correlationId),
    };
    if (traceparent) attributes["traceparent"] = attr(traceparent);
    await this.sns.send(
      new PublishCommand({
        TopicArn: topicArn,
        Message: serializeEnvelope(env),
        MessageAttributes: attributes,
      }),
    );
  }
}
