import { SNSClient } from "@aws-sdk/client-sns";
import { SQSClient } from "@aws-sdk/client-sqs";
import { loadConfig, type Config } from "./app/config.js";
import { buildServer } from "./app/server.js";
import { registerOrderRoutes } from "./modules/orders/http/routes.js";
import { createSagaHandlers } from "./modules/orders/application/saga.js";
import { SqsConsumer } from "./platform/consumer.js";
import { createValidator } from "./platform/validator.js";
import { createPool } from "./platform/db.js";
import { createLogger } from "./platform/logging.js";
import { awsClientConfig, SnsPublisher } from "./platform/publisher.js";
import { OutboxRelay } from "./platform/relay.js";

let config: Config;
try {
  config = loadConfig(process.env);
} catch (error) {
  const message = error instanceof Error ? error.message : String(error);
  process.stderr.write(
    `${JSON.stringify({ level: "error", message: "invalid_config", error: message })}\n`,
  );
  process.exit(1);
}

// API role (RLS) for requests and readiness; worker role for the relay and, in F2, the consumers.
// Pools connect lazily: the service starts even if the database is still coming up (readiness says so).
const logger = createLogger(config.serviceName, config.logLevel);
const appPool = createPool(config.databaseUrl, logger);
const workerPool = createPool(config.databaseWorkerUrl, logger);

const sns = new SNSClient({
  ...awsClientConfig(config.httpClientTimeoutMs),
  region: config.awsRegion,
  ...(config.awsEndpointUrl ? { endpoint: config.awsEndpointUrl } : {}),
});

const app = buildServer(config, {
  pingDb: async () => {
    await appPool.query("SELECT 1");
  },
  brokerUp: () => relay.brokerUp && consumer.brokerUp,
});
registerOrderRoutes(app, {
  pool: appPool,
  topicArn: config.topicArn,
  maxQuantity: config.maxQuantity,
  orderCreatedVersion: config.orderCreatedVersion,
});

const relay = new OutboxRelay(workerPool, new SnsPublisher(sns), config.outbox, logger);
// The consumer's client allows the long poll (SQS_WAIT_TIME_S); each receive passes its own timeout.
const sqs = new SQSClient({
  ...awsClientConfig(config.httpClientTimeoutMs),
  region: config.awsRegion,
  ...(config.awsEndpointUrl ? { endpoint: config.awsEndpointUrl } : {}),
});
const consumer = new SqsConsumer(
  sqs,
  workerPool,
  await createValidator(config.contractsDir),
  createSagaHandlers({ topicArn: config.topicArn, paymentTimeoutMs: config.paymentTimeoutMs }),
  {
    queueUrl: config.queueUrl,
    name: config.serviceName,
    concurrency: config.consumer.concurrency,
    waitTimeSeconds: config.consumer.waitTimeSeconds,
    requestTimeoutMs: config.httpClientTimeoutMs,
  },
  logger,
);
const stopRelay = new AbortController();
const relayDone = relay.run(stopRelay.signal);
const consumerDone = consumer.run(stopRelay.signal);

const shutdown = async (signal: string): Promise<void> => {
  app.log.info({ signal }, "http_server_stopping");
  stopRelay.abort(); // the current batch finishes; unpublished rows stay in the outbox
  await app.close();
  await Promise.all([relayDone, consumerDone]);
  await Promise.all([appPool.end(), workerPool.end()]);
  process.exit(0);
};
process.once("SIGTERM", () => void shutdown("SIGTERM"));
process.once("SIGINT", () => void shutdown("SIGINT"));

await app.listen({ host: "0.0.0.0", port: config.port });
