import { loadConfig, type Config } from "./app/config.js";
import { buildServer } from "./app/server.js";

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

const app = buildServer(config);

const shutdown = async (signal: string): Promise<void> => {
  app.log.info({ signal }, "http_server_stopping");
  await app.close();
  process.exit(0);
};
process.once("SIGTERM", () => void shutdown("SIGTERM"));
process.once("SIGINT", () => void shutdown("SIGINT"));

await app.listen({ host: "0.0.0.0", port: config.port });
