import { readFileSync } from "node:fs";
import { CreateTopicCommand, SNSClient, SubscribeCommand } from "@aws-sdk/client-sns";
import { CreateQueueCommand, GetQueueAttributesCommand, SQSClient } from "@aws-sdk/client-sqs";
import { PostgreSqlContainer, type StartedPostgreSqlContainer } from "@testcontainers/postgresql";
import pg from "pg";
import {
  GenericContainer,
  getContainerRuntimeClient,
  type StartedTestContainer,
  Wait,
} from "testcontainers";
import { awsClientConfig } from "../../../src/platform/publisher.js";

/**
 * Shared Postgres 16 (platform tables) and MiniStack containers for the platform integration tests.
 * Started once per test file; tables are truncated on every call.
 */
export const CLIENT_TIMEOUT_MS = 2000;

let postgres: StartedPostgreSqlContainer | undefined;
let ministack: StartedTestContainer | undefined;

export async function postgresPool(): Promise<pg.Pool> {
  if (!postgres) {
    postgres = await new PostgreSqlContainer("postgres:16-alpine").start();
    const setup = new pg.Pool({ connectionString: postgres.getConnectionUri() });
    await setup.query(readFileSync("tests/fixtures/platform.sql", "utf8"));
    await setup.end();
  }
  const pool = new pg.Pool({ connectionString: postgres.getConnectionUri() });
  await pool.query("TRUNCATE outbox, processed_messages, effects RESTART IDENTITY");
  return pool;
}

export async function startMiniStack(): Promise<StartedTestContainer> {
  ministack ??= await new GenericContainer("ministackorg/ministack:1.5.40")
    .withEnvironment({ MINISTACK_REGION: "us-east-1" })
    .withExposedPorts(4566)
    .withWaitStrategy(Wait.forHttp("/_ministack/health", 4566))
    .start();
  return ministack;
}

async function awsOptions(timeoutMs: number) {
  const ms = await startMiniStack();
  return {
    ...awsClientConfig(timeoutMs),
    endpoint: `http://${ms.getHost()}:${String(ms.getMappedPort(4566))}`,
    region: "us-east-1",
    credentials: { accessKeyId: "test", secretAccessKey: "test" },
  };
}

export async function sqsClient(timeoutMs = CLIENT_TIMEOUT_MS): Promise<SQSClient> {
  return new SQSClient(await awsOptions(timeoutMs));
}

export async function snsClient(): Promise<SNSClient> {
  return new SNSClient(await awsOptions(CLIENT_TIMEOUT_MS));
}

/** Freezes MiniStack (same as `docker pause`, the F6 scenario) through the Docker API. */
export async function pauseMiniStack(): Promise<void> {
  const client = await getContainerRuntimeClient();
  await client.container.getById((await startMiniStack()).getId()).pause();
}

export async function unpauseMiniStack(): Promise<void> {
  const client = await getContainerRuntimeClient();
  await client.container.getById((await startMiniStack()).getId()).unpause();
}

export async function queueArn(sqs: SQSClient, url: string): Promise<string> {
  const out = await sqs.send(
    new GetQueueAttributesCommand({ QueueUrl: url, AttributeNames: ["QueueArn"] }),
  );
  return out.Attributes?.["QueueArn"] ?? "";
}

/** Creates a queue with a DLQ (redrive after maxReceiveCount); returns both URLs. */
export async function createQueue(
  sqs: SQSClient,
  name: string,
  maxReceiveCount: number,
): Promise<{ queueUrl: string; dlqUrl: string }> {
  const unique = `${name}-${String(Date.now())}-${String(Math.floor(Math.random() * 1e6))}`;
  const dlq = await sqs.send(new CreateQueueCommand({ QueueName: `${unique}-dlq` }));
  const dlqUrl = dlq.QueueUrl ?? "";
  const redrive = JSON.stringify({
    deadLetterTargetArn: await queueArn(sqs, dlqUrl),
    maxReceiveCount: String(maxReceiveCount),
  });
  const queue = await sqs.send(
    new CreateQueueCommand({
      QueueName: unique,
      Attributes: { VisibilityTimeout: "30", RedrivePolicy: redrive },
    }),
  );
  return { queueUrl: queue.QueueUrl ?? "", dlqUrl };
}

/** Topic subscribed by the queue with raw message delivery; returns its ARN. */
export async function createTopic(
  sns: SNSClient,
  sqs: SQSClient,
  name: string,
  queueUrl: string,
): Promise<string> {
  const topic = await sns.send(new CreateTopicCommand({ Name: `${name}-${String(Date.now())}` }));
  const arn = topic.TopicArn ?? "";
  await sns.send(
    new SubscribeCommand({
      TopicArn: arn,
      Protocol: "sqs",
      Endpoint: await queueArn(sqs, queueUrl),
      Attributes: { RawMessageDelivery: "true" },
    }),
  );
  return arn;
}

export async function queueDepth(sqs: SQSClient, url: string): Promise<number> {
  const out = await sqs.send(
    new GetQueueAttributesCommand({
      QueueUrl: url,
      AttributeNames: ["ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible"],
    }),
  );
  return (
    Number(out.Attributes?.["ApproximateNumberOfMessages"] ?? 0) +
    Number(out.Attributes?.["ApproximateNumberOfMessagesNotVisible"] ?? 0)
  );
}

export async function count(pool: pg.Pool, sql: string, params: unknown[] = []): Promise<number> {
  // Queries alias their single value as n: SELECT count(*) AS n …
  const result = await pool.query<{ n: string }>(sql, params);
  return Number(result.rows[0]?.n ?? 0);
}
