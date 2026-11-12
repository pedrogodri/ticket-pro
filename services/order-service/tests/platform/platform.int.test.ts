import { readFileSync } from "node:fs";
import { join } from "node:path";
import { Writable } from "node:stream";
import { context, trace, TraceFlags } from "@opentelemetry/api";
import {
  ReceiveMessageCommand,
  SendMessageCommand,
  type Message,
  type SQSClient,
} from "@aws-sdk/client-sqs";
import type { PoolClient } from "pg";
import type pg from "pg";
import { afterAll, describe, expect, it } from "vitest";
import {
  SqsConsumer,
  type ConsumeOutcome,
  type MessageHandler,
} from "../../src/platform/consumer.js";
import {
  createEnvelope,
  StaleEventError,
  unwrap,
  type Envelope,
} from "../../src/platform/envelope.js";
import { recordProcessed } from "../../src/platform/idempotency.js";
import { createLogger } from "../../src/platform/logging.js";
import { enqueue, type Publisher } from "../../src/platform/outbox.js";
import { SnsPublisher } from "../../src/platform/publisher.js";
import { OutboxRelay } from "../../src/platform/relay.js";
import { createValidator } from "../../src/platform/validator.js";
import {
  CLIENT_TIMEOUT_MS,
  count,
  createQueue,
  createTopic,
  pauseMiniStack,
  postgresPool,
  queueDepth,
  snsClient,
  sqsClient,
  unpauseMiniStack,
} from "./support/containers.js";

const CONTRACTS = "contracts";
const TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:ticketpro-test";
const MSG_ID = "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55";
const quiet = createLogger(
  "test",
  "error",
  new Writable({
    write: (_c, _e, done) => {
      done();
    },
  }),
);
const pools: pg.Pool[] = [];

afterAll(async () => {
  await Promise.all(pools.map((p) => p.end()));
});

async function pool(): Promise<pg.Pool> {
  const p = await postgresPool();
  pools.push(p);
  return p;
}

const newEnvelope = (): Envelope =>
  createEnvelope("c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c", "Ping", 1, { n: 1 });

async function inTx<T>(p: pg.Pool, fn: (c: PoolClient) => Promise<T>, commit = true): Promise<T> {
  const client = await p.connect();
  try {
    await client.query("BEGIN");
    const result = await fn(client);
    await client.query(commit ? "COMMIT" : "ROLLBACK");
    return result;
  } finally {
    client.release();
  }
}

const withSpan = <T>(fn: () => Promise<T>): Promise<T> =>
  context.with(
    trace.setSpanContext(context.active(), {
      traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
      spanId: "00f067aa0ba902b7",
      traceFlags: TraceFlags.SAMPLED,
    }),
    fn,
  );

const relayOptions = (batchSize: number) => ({
  batchSize,
  pollIntervalMs: 50,
  maxBackoffMs: 1000,
  claimLeaseMs: 30_000,
});

describe("platform integration (P07–P15)", () => {
  it("P07 record is fresh once per consumer and message", async () => {
    const p = await pool();
    const record = (consumer: string) => inTx(p, (c) => recordProcessed(c, consumer, MSG_ID));
    expect(await record("c1")).toBe(true);
    expect(await record("c1")).toBe(false);
    expect(await record("c2")).toBe(true);
  });

  it("P08 enqueue is part of the caller transaction", async () => {
    const p = await pool();
    const committed = newEnvelope();
    await inTx(p, (c) => enqueue(c, TOPIC_ARN, committed));
    await inTx(p, (c) => enqueue(c, TOPIC_ARN, newEnvelope()), false);
    expect(await count(p, "SELECT count(*) AS n FROM outbox")).toBe(1);
    expect(
      await count(
        p,
        `SELECT count(*) AS n FROM outbox WHERE message_id = $1 AND type = 'Ping' AND version = 1
           AND correlation_id = $2 AND payload = '{"n": 1}'::jsonb AND traceparent IS NULL`,
        [committed.messageId, committed.correlationId],
      ),
    ).toBe(1);
    const traced = newEnvelope();
    await withSpan(() => inTx(p, (c) => enqueue(c, TOPIC_ARN, traced)));
    expect(
      await count(
        p,
        "SELECT count(*) AS n FROM outbox WHERE message_id = $1 AND traceparent LIKE '00-%'",
        [traced.messageId],
      ),
    ).toBe(1);
  });

  it("P12 relay accumulates while the broker is down and drains after", async () => {
    const p = await pool();
    const sqs = await sqsClient();
    const sns = await snsClient();
    const { queueUrl } = await createQueue(sqs, "relay", 3);
    const topic = await createTopic(sns, sqs, "relay", queueUrl);
    const envs = [newEnvelope(), newEnvelope(), newEnvelope()] as const;
    await withSpan(() => inTx(p, (c) => enqueue(c, topic, envs[0])));
    await inTx(p, (c) => enqueue(c, topic, envs[1]));
    await inTx(p, (c) => enqueue(c, topic, envs[2]));

    const relay = new OutboxRelay(p, new SnsPublisher(sns), relayOptions(10), quiet);
    await pauseMiniStack();
    try {
      await expect(relay.runOnce()).rejects.toThrow();
      expect(relay.brokerUp).toBe(false);
      expect(await count(p, "SELECT count(*) AS n FROM outbox WHERE published_at IS NULL")).toBe(3);
      expect(
        await count(p, "SELECT coalesce(sum(attempts),0) AS n FROM outbox"),
      ).toBeGreaterThanOrEqual(1);
    } finally {
      await unpauseMiniStack();
    }

    const controller = new AbortController();
    const run = relay.run(controller.signal);
    const deadline = Date.now() + 30_000;
    while ((await count(p, "SELECT count(*) AS n FROM outbox WHERE published_at IS NULL")) > 0) {
      expect(Date.now()).toBeLessThan(deadline);
      await new Promise((r) => setTimeout(r, 100));
    }
    controller.abort();
    await run;
    expect(relay.brokerUp).toBe(true);

    const got = new Map<string, Message>();
    while (got.size < 3 && Date.now() < deadline + 10_000) {
      const out = await sqs.send(
        new ReceiveMessageCommand({
          QueueUrl: queueUrl,
          MaxNumberOfMessages: 10,
          WaitTimeSeconds: 1,
          MessageAttributeNames: ["All"],
        }),
      );
      for (const m of out.Messages ?? []) got.set(unwrap(m.Body ?? "").messageId, m);
    }
    envs.forEach((env, i) => {
      const attrs = got.get(env.messageId)?.MessageAttributes ?? {};
      expect(attrs["type"]?.StringValue).toBe("Ping");
      expect(attrs["version"]?.StringValue).toBe("1");
      expect(attrs["correlationId"]?.StringValue).toBe(env.correlationId);
      expect("traceparent" in attrs).toBe(i === 0);
    });
  }, 90_000);

  it("P13 concurrent relays publish each row once", async () => {
    const p = await pool();
    for (let i = 0; i < 20; i++) await inTx(p, (c) => enqueue(c, TOPIC_ARN, newEnvelope()));
    const calls = new Map<string, number>();
    const counting: Publisher = {
      publish: (_topic, env) => {
        calls.set(env.messageId, (calls.get(env.messageId) ?? 0) + 1);
        return Promise.resolve();
      },
    };
    const drain = async () => {
      const relay = new OutboxRelay(p, counting, relayOptions(3), quiet);
      while ((await count(p, "SELECT count(*) AS n FROM outbox WHERE published_at IS NULL")) > 0) {
        await relay.runOnce();
      }
    };
    await Promise.all([drain(), drain()]);
    expect(calls.size).toBe(20);
    expect([...calls.values()].every((n) => n === 1)).toBe(true);
  });
});

// ---------- consumer ----------

class EffectHandler implements MessageHandler {
  readonly versions: ReadonlySet<number> = new Set([1]);
  private calls = 0;
  constructor(
    private readonly failFirst = false,
    private readonly result?: Error,
  ) {}

  async handle(env: Envelope, client: PoolClient): Promise<void> {
    this.calls++;
    if (this.calls === 1 && this.failFirst) throw new Error("database temporarily unavailable");
    await client.query("INSERT INTO effects (message_id) VALUES ($1)", [env.messageId]);
    if (this.result) throw this.result;
  }
}

function example(name: string, messageId: string): string {
  const node = JSON.parse(
    readFileSync(join(CONTRACTS, "examples", `${name}.json`), "utf8"),
  ) as Record<string, unknown>;
  node["messageId"] = messageId;
  return JSON.stringify(node);
}

async function fixture(handler: MessageHandler) {
  const p = await pool();
  const sqs = await sqsClient();
  const { queueUrl, dlqUrl } = await createQueue(sqs, "consumer", 3);
  const consumer = new SqsConsumer(
    sqs,
    p,
    await createValidator(CONTRACTS),
    new Map([["PaymentFailed", handler]]),
    {
      queueUrl,
      name: "test-consumer",
      concurrency: 4,
      waitTimeSeconds: 1,
      requestTimeoutMs: CLIENT_TIMEOUT_MS,
    },
    quiet,
  );
  const send = (body: string) =>
    sqs.send(new SendMessageCommand({ QueueUrl: queueUrl, MessageBody: body }));
  const receiveAndProcess = async (): Promise<ConsumeOutcome[]> => {
    const out = await sqs.send(
      new ReceiveMessageCommand({
        QueueUrl: queueUrl,
        MaxNumberOfMessages: 10,
        WaitTimeSeconds: 1,
        MessageAttributeNames: ["All"],
        MessageSystemAttributeNames: ["ApproximateReceiveCount"],
      }),
    );
    const outcomes: ConsumeOutcome[] = [];
    for (const m of out.Messages ?? []) outcomes.push(await consumer.process(m));
    return outcomes;
  };
  return { p, sqs, queueUrl, dlqUrl, send, receiveAndProcess };
}

const countOf = (outcomes: ConsumeOutcome[], want: ConsumeOutcome) =>
  outcomes.filter((o) => o === want).length;

describe("consumer integration", () => {
  it("P09 duplicate five times has one effect", async () => {
    const f = await fixture(new EffectHandler());
    const body = example("PaymentFailed.v1", MSG_ID);
    for (let i = 0; i < 5; i++) await f.send(body);
    const outcomes: ConsumeOutcome[] = [];
    for (let i = 0; i < 10 && outcomes.length < 5; i++)
      outcomes.push(...(await f.receiveAndProcess()));
    expect(countOf(outcomes, "succeeded")).toBe(1);
    expect(countOf(outcomes, "duplicate")).toBe(4);
    expect(await count(f.p, "SELECT count(*) AS n FROM effects")).toBe(1);
    expect(await count(f.p, "SELECT count(*) AS n FROM processed_messages")).toBe(1);
    expect(await queueDepth(f.sqs, f.queueUrl)).toBe(0);
  });

  it("P10 crash before commit is redelivered", async () => {
    const f = await fixture(new EffectHandler(true));
    await f.send(example("PaymentFailed.v1", MSG_ID));
    const outcomes: ConsumeOutcome[] = [];
    const deadline = Date.now() + 20_000;
    while (!outcomes.includes("succeeded") && Date.now() < deadline)
      outcomes.push(...(await f.receiveAndProcess()));
    expect(outcomes[0]).toBe("transient");
    expect(outcomes.at(-1)).toBe("succeeded");
    expect(await count(f.p, "SELECT count(*) AS n FROM effects")).toBe(1);
  }, 30_000);

  it("P11 poison goes to the DLQ", async () => {
    const f = await fixture(new EffectHandler());
    await f.send(
      example("PaymentFailed.v1", MSG_ID).replace('"reason":"PROVIDER_TIMEOUT"', '"reason":"NOPE"'),
    );
    for (let i = 0; i < 6 && (await queueDepth(f.sqs, f.dlqUrl)) === 0; i++) {
      expect((await f.receiveAndProcess()).every((o) => o === "poison")).toBe(true);
    }
    expect(await queueDepth(f.sqs, f.dlqUrl)).toBe(1);
    expect(await count(f.p, "SELECT count(*) AS n FROM effects")).toBe(0);

    // A valid message whose type has no handler is poison too, and is not acknowledged.
    await f.send(example("InventoryReserved.v1", "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a56"));
    expect(await f.receiveAndProcess()).toEqual(["poison"]);
    expect(await queueDepth(f.sqs, f.queueUrl)).toBe(1);
  }, 30_000);

  it("P14 stale event is recorded and acknowledged", async () => {
    const f = await fixture(new EffectHandler(false, new StaleEventError("order already final")));
    await f.send(example("PaymentFailed.v1", MSG_ID));
    expect(await f.receiveAndProcess()).toEqual(["stale"]);
    expect(await count(f.p, "SELECT count(*) AS n FROM processed_messages")).toBe(1);
    expect(await queueDepth(f.sqs, f.queueUrl)).toBe(0);
  });

  it("P15 raw and SNS formats are both consumed", async () => {
    const f = await fixture(new EffectHandler());
    const sns = await snsClient();
    const topic = await createTopic(sns, f.sqs, "formats", f.queueUrl);
    await new SnsPublisher(sns).publish(
      topic,
      unwrap(example("PaymentFailed.v1", MSG_ID)),
      undefined,
    );
    await f.send(
      JSON.stringify({
        Type: "Notification",
        MessageId: "n-1",
        TopicArn: topic,
        Message: example("PaymentFailed.v1", "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a57"),
      }),
    );
    const outcomes: ConsumeOutcome[] = [];
    for (let i = 0; i < 10 && outcomes.length < 2; i++)
      outcomes.push(...(await f.receiveAndProcess()));
    expect(countOf(outcomes, "succeeded")).toBe(2);
  });

  // SQS_WAIT_TIME_S (10) is longer than HTTP_CLIENT_TIMEOUT_MS (3000): the receive must use a longer
  // timeout than the client-wide one, or every long poll fails.
  it("run long-polls longer than the client timeout", async () => {
    const p = await pool();
    const sqs: SQSClient = await sqsClient();
    const { queueUrl } = await createQueue(sqs, "long-poll", 3);
    const consumer = new SqsConsumer(
      sqs,
      p,
      await createValidator(CONTRACTS),
      new Map([["PaymentFailed", new EffectHandler()]]),
      {
        queueUrl,
        name: "long-poll",
        concurrency: 4,
        waitTimeSeconds: CLIENT_TIMEOUT_MS / 1000 + 2,
        requestTimeoutMs: CLIENT_TIMEOUT_MS,
      },
      quiet,
    );
    const controller = new AbortController();
    const run = consumer.run(controller.signal);
    await new Promise((r) => setTimeout(r, CLIENT_TIMEOUT_MS + 1000));
    await sqs.send(
      new SendMessageCommand({
        QueueUrl: queueUrl,
        MessageBody: example("PaymentFailed.v1", MSG_ID),
      }),
    );
    const deadline = Date.now() + 15_000;
    while ((await count(p, "SELECT count(*) AS n FROM effects")) === 0) {
      expect(Date.now()).toBeLessThan(deadline);
      await new Promise((r) => setTimeout(r, 200));
    }
    expect(consumer.receiveFailures).toBe(0);
    controller.abort();
    await run;
  }, 30_000);
});
