import { Writable } from "node:stream";
import { SQSClient } from "@aws-sdk/client-sqs";
import type pg from "pg";
import { describe, expect, it } from "vitest";
import { SqsConsumer } from "../../src/platform/consumer.js";
import { createLogger } from "../../src/platform/logging.js";
import { awsClientConfig } from "../../src/platform/publisher.js";

const quiet = createLogger("test", "error", new Writable({ write: (_c, _e, done) => { done(); } }));
// Unreachable SQS: ack/visibility calls fail fast; process() must still return an outcome.
const deadSqs = new SQSClient({
  ...awsClientConfig(200),
  endpoint: "http://127.0.0.1:1",
  region: "us-east-1",
  credentials: { accessKeyId: "test", secretAccessKey: "test" },
});
const options = { queueUrl: "http://127.0.0.1:1/q", name: "c", concurrency: 1, waitTimeSeconds: 1, requestTimeoutMs: 200 };

describe("SqsConsumer.process never throws (review finding)", () => {
  it("an unexpected validator error is transient, not a crash", async () => {
    const consumer = new SqsConsumer(
      deadSqs,
      {} as pg.Pool,
      { validate: () => { throw new TypeError("boom"); } },
      new Map(),
      options,
      quiet,
    );
    await expect(consumer.process({ Body: "{}", ReceiptHandle: "r" })).resolves.toBe("transient");
  });
});
