import { SNSClient } from "@aws-sdk/client-sns";
import { loadConfig, type Config } from "./app/config.js";
import { buildServer } from "./app/server.js";
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
  brokerUp: () => relay.brokerUp,
});
const relay = new OutboxRelay(workerPool, new SnsPublisher(sns), config.outbox, logger);
const stopRelay = new AbortController();
const relayDone = relay.run(stopRelay.signal);

const shutdown = async (signal: string): Promise<void> => {
  app.log.info({ signal }, "http_server_stopping");
  stopRelay.abort(); // the current batch finishes; unpublished rows stay in the outbox
  await app.close();
  await relayDone;
  await Promise.all([appPool.end(), workerPool.end()]);
  process.exit(0);
};
process.once("SIGTERM", () => void shutdown("SIGTERM"));
process.once("SIGINT", () => void shutdown("SIGINT"));

await app.listen({ host: "0.0.0.0", port: config.port });
