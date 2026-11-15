import {
  ChangeMessageVisibilityCommand,
  DeleteMessageCommand,
  ReceiveMessageCommand,
  type Message,
  type SQSClient,
} from "@aws-sdk/client-sqs";
import { context } from "@opentelemetry/api";
import type { Pool, PoolClient } from "pg";
import {
  PoisonMessageError,
  rawEnvelope,
  StaleEventError,
  unwrap,
  UnsupportedVersionError,
  type Envelope,
} from "./envelope.js";
import { recordProcessed } from "./idempotency.js";
import { withLogContext, type Logger } from "./logging.js";
import { delay, DEFAULT_BASE_MS, DEFAULT_CAP_MS } from "./retry.js";
import { extract } from "./tracing.js";
import type { ContractValidator } from "./validator.js";

/**
 * Applies one event inside the consumer's transaction. Resolve (success), throw StaleEventError,
 * PoisonMessageError, or anything else (transient).
 */
export interface MessageHandler {
  readonly versions: ReadonlySet<number>;
  handle(env: Envelope, client: PoolClient): Promise<void>;
}

/** What process() did with one message (RFC 0003 §5.4). */
export type ConsumeOutcome = "succeeded" | "duplicate" | "stale" | "poison" | "transient";

/** Consumer settings: QUEUE_URL, name, CONSUMER_CONCURRENCY, SQS_WAIT_TIME_S, HTTP_CLIENT_TIMEOUT_MS. */
export interface ConsumerOptions {
  queueUrl: string;
  name: string;
  concurrency: number;
  waitTimeSeconds: number;
  /** HTTP timeout of the other SQS calls; the long poll uses waitTimeSeconds + this. */
  requestTimeoutMs: number;
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

/**
 * Receives from one SQS queue and applies handlers with ack-after-commit (ADR-0006, RFC 0003 §5.4).
 * The pool must use the worker role (DATABASE_WORKER_URL).
 */
export class SqsConsumer {
  #brokerUp = true;
  #receiveFailures = 0;

  constructor(
    private readonly sqs: SQSClient,
    private readonly pool: Pool,
    private readonly validator: ContractValidator,
    private readonly handlers: ReadonlyMap<string, MessageHandler>,
    private readonly opts: ConsumerOptions,
    private readonly logger: Logger,
  ) {}

  /** Whether the last receive succeeded. */
  get brokerUp(): boolean {
    return this.#brokerUp;
  }

  /** Receive calls that failed since start (broker down, timeouts). */
  get receiveFailures(): number {
    return this.#receiveFailures;
  }

  /** Receives and processes until signal aborts; receive errors back off. In-flight messages finish. */
  async run(signal: AbortSignal): Promise<void> {
    let failures = 0;
    while (!signal.aborted) {
      let messages: Message[];
      try {
        const out = await this.sqs.send(
          new ReceiveMessageCommand({
            QueueUrl: this.opts.queueUrl,
            MaxNumberOfMessages: Math.min(Math.max(this.opts.concurrency, 1), 10),
            WaitTimeSeconds: this.opts.waitTimeSeconds,
            MessageAttributeNames: ["All"],
            MessageSystemAttributeNames: ["ApproximateReceiveCount"],
          }),
          // The client-wide timeout (HTTP_CLIENT_TIMEOUT_MS) is shorter than the long poll (SQS_WAIT_TIME_S).
          { requestTimeout: this.opts.waitTimeSeconds * 1000 + this.opts.requestTimeoutMs },
        );
        messages = out.Messages ?? [];
      } catch (error) {
        // The await above may have been interrupted by an abort; TS narrowed `aborted` to false before it.
        // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
        if (signal.aborted) return;
        this.#receiveFailures++;
        if (this.#brokerUp) {
          this.#brokerUp = false;
          this.logger.warn({ error: String(error) }, "broker_down");
        }
        await sleep(delay(failures++, DEFAULT_BASE_MS, DEFAULT_CAP_MS, Math.random), signal);
        continue;
      }
      if (!this.#brokerUp) {
        this.#brokerUp = true;
        this.logger.info("broker_up");
      }
      failures = 0;
      await Promise.all(messages.map((m) => this.process(m)));
    }
  }

  /** Applies one message following RFC 0003 §5.4 and acknowledges only after commit. */
  async process(message: Message): Promise<ConsumeOutcome> {
    const body = message.Body ?? "";
    let env: Envelope;
    try {
      this.validator.validate(rawEnvelope(body));
      env = unwrap(body);
    } catch (error) {
      if (error instanceof PoisonMessageError) return this.poison(message, error);
      return this.transient(message, error); // unexpected: retry via SQS, never crash the loop
    }
    const logContext = {
      correlationId: env.correlationId,
      messageId: env.messageId,
      type: env.type,
      version: env.version,
    };
    return withLogContext(logContext, () =>
      context.with(extract(traceparentOf(message)), async () => {
        const handler = this.handlers.get(env.type);
        if (!handler?.versions.has(env.version)) {
          return this.poison(
            message,
            new UnsupportedVersionError(`no handler for ${env.type} v${String(env.version)}`),
          );
        }
        return this.apply(message, env, handler);
      }),
    );
  }

  private async apply(
    message: Message,
    env: Envelope,
    handler: MessageHandler,
  ): Promise<ConsumeOutcome> {
    let client: PoolClient;
    try {
      client = await this.pool.connect();
    } catch (error) {
      return this.transient(message, error);
    }
    try {
      await client.query("BEGIN");
      if (!(await recordProcessed(client, this.opts.name, env.messageId))) {
        await client.query("ROLLBACK");
        await this.ack(message);
        this.logger.info({ duplicate: true }, "message_consumed");
        return "duplicate";
      }
      let stale = false;
      try {
        await handler.handle(env, client);
      } catch (error) {
        if (!(error instanceof StaleEventError)) throw error;
        stale = true;
      }
      await client.query("COMMIT");
      await this.ack(message);
      if (stale) {
        this.logger.info("stale_event");
        return "stale";
      }
      this.logger.info("message_consumed");
      return "succeeded";
    } catch (error) {
      await client.query("ROLLBACK").catch(() => undefined); // broken connection: the server discards it
      if (error instanceof PoisonMessageError) return await this.poison(message, error);
      return await this.transient(message, error);
    } finally {
      client.release();
    }
  }

  private async poison(message: Message, cause: PoisonMessageError): Promise<ConsumeOutcome> {
    this.logger.warn(
      {
        poison: true,
        reason:
          cause instanceof UnsupportedVersionError ? "unsupported_version" : "invalid_message",
        error: cause.message,
      },
      "message_rejected",
    );
    await this.changeVisibility(message, 0); // reach maxReceiveCount fast → DLQ via redrive
    return "poison";
  }

  private async transient(message: Message, cause: unknown): Promise<ConsumeOutcome> {
    const receiveCount = Number(message.Attributes?.ApproximateReceiveCount ?? 0);
    const seconds = Math.max(
      1,
      Math.ceil(delay(receiveCount, DEFAULT_BASE_MS, DEFAULT_CAP_MS, Math.random) / 1000),
    );
    this.logger.warn({ error: String(cause), receiveCount, visibilityS: seconds }, "message_retry");
    await this.changeVisibility(message, seconds);
    return "transient";
  }

  private async ack(message: Message): Promise<void> {
    try {
      await this.sqs.send(
        new DeleteMessageCommand({
          QueueUrl: this.opts.queueUrl,
          ReceiptHandle: message.ReceiptHandle,
        }),
      );
    } catch (error) {
      // Committed but not acked: the redelivery is detected as a duplicate.
      this.logger.warn({ error: String(error) }, "ack_failed");
    }
  }

  private async changeVisibility(message: Message, seconds: number): Promise<void> {
    try {
      await this.sqs.send(
        new ChangeMessageVisibilityCommand({
          QueueUrl: this.opts.queueUrl,
          ReceiptHandle: message.ReceiptHandle,
          VisibilityTimeout: seconds,
        }),
      );
    } catch (error) {
      this.logger.warn({ error: String(error) }, "change_visibility_failed");
    }
  }
}

// Raw delivery puts traceparent in the SQS attributes; an SNS notification keeps it inside the body (spike S2).
function traceparentOf(message: Message): string | undefined {
  const attr = message.MessageAttributes?.["traceparent"]?.StringValue;
  if (attr) return attr;
  try {
    const parsed = JSON.parse(message.Body ?? "") as {
      MessageAttributes?: { traceparent?: { Value?: unknown } };
    };
    const value = parsed.MessageAttributes?.traceparent?.Value;
    return typeof value === "string" ? value : undefined;
  } catch {
    return undefined;
  }
}
