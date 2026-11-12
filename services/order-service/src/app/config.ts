import { z } from "zod";
import type { LogLevel } from "../platform/logging.js";
import type { RelayOptions } from "../platform/relay.js";

export type { LogLevel };

export interface Config {
  serviceName: string;
  port: number;
  logLevel: LogLevel;
  /** API role (RLS applies), docs/padroes.md §6.1. */
  databaseUrl: string;
  /** Worker role: outbox relay, consumers, jobs. */
  databaseWorkerUrl: string;
  /** ORDERS_TOPIC_ARN */
  topicArn: string;
  outbox: RelayOptions;
  httpClientTimeoutMs: number;
  awsRegion: string;
  /** MiniStack endpoint; undefined uses the SDK default. */
  awsEndpointUrl: string | undefined;
}

const required = (name: string) => z.string().trim().min(1, `${name} is required`);
const postgresUrl = (name: string) =>
  required(name).regex(
    /^postgres(ql)?:\/\/[^:@/]+:[^@]*@[^/]+\/.+/,
    `${name} must be postgres://user:password@host:port/database`,
  );
const positiveInt = (name: string, fallback: number) =>
  z.coerce
    .number({ error: `${name} must be a positive integer` })
    .int(`${name} must be a positive integer`)
    .min(1, `${name} must be a positive integer`)
    .default(fallback);

const envSchema = z.object({
  SERVICE_NAME: required("SERVICE_NAME"),
  PORT: z.coerce
    .number({ error: "PORT must be an integer between 1 and 65535" })
    .int("PORT must be an integer between 1 and 65535")
    .min(1, "PORT must be an integer between 1 and 65535")
    .max(65535, "PORT must be an integer between 1 and 65535"),
  LOG_LEVEL: z.enum(["debug", "info", "warn", "error"], {
    error: "LOG_LEVEL must be one of debug, info, warn, error",
  }),
  DATABASE_URL: postgresUrl("DATABASE_URL"),
  DATABASE_WORKER_URL: postgresUrl("DATABASE_WORKER_URL"),
  ORDERS_TOPIC_ARN: required("ORDERS_TOPIC_ARN"),
  AWS_REGION: required("AWS_REGION"),
  AWS_ENDPOINT_URL: z.string().trim().optional(),
  OUTBOX_BATCH_SIZE: positiveInt("OUTBOX_BATCH_SIZE", 50),
  OUTBOX_POLL_INTERVAL_MS: positiveInt("OUTBOX_POLL_INTERVAL_MS", 200),
  OUTBOX_MAX_BACKOFF_MS: positiveInt("OUTBOX_MAX_BACKOFF_MS", 30_000),
  OUTBOX_CLAIM_LEASE_MS: positiveInt("OUTBOX_CLAIM_LEASE_MS", 30_000),
  HTTP_CLIENT_TIMEOUT_MS: positiveInt("HTTP_CLIENT_TIMEOUT_MS", 3000),
});

const keys = Object.keys(envSchema.shape) as (keyof typeof envSchema.shape)[];

/**
 * Reads and validates configuration from environment variables (docs/padroes.md §8).
 * Throws an Error whose message names every offending variable.
 */
export function loadConfig(env: NodeJS.ProcessEnv): Config {
  // Empty strings count as missing, so optional variables fall back to their defaults.
  const input = Object.fromEntries(keys.map((k) => [k, env[k] === "" ? undefined : env[k]]));
  const parsed = envSchema.safeParse({
    ...input,
    SERVICE_NAME: input["SERVICE_NAME"] ?? "",
    PORT: input["PORT"] ?? "",
    LOG_LEVEL: env["LOG_LEVEL"]?.toLowerCase() ?? "",
    DATABASE_URL: input["DATABASE_URL"] ?? "",
    DATABASE_WORKER_URL: input["DATABASE_WORKER_URL"] ?? "",
    ORDERS_TOPIC_ARN: input["ORDERS_TOPIC_ARN"] ?? "",
    AWS_REGION: input["AWS_REGION"] ?? "",
  });
  if (!parsed.success) {
    const problems = parsed.error.issues.map(
      (issue) => `${issue.path.join(".")}: ${issue.message}`,
    );
    throw new Error(`Invalid configuration: ${problems.join("; ")}`);
  }
  const e = parsed.data;
  return {
    serviceName: e.SERVICE_NAME,
    port: e.PORT,
    logLevel: e.LOG_LEVEL,
    databaseUrl: e.DATABASE_URL,
    databaseWorkerUrl: e.DATABASE_WORKER_URL,
    topicArn: e.ORDERS_TOPIC_ARN,
    outbox: {
      batchSize: e.OUTBOX_BATCH_SIZE,
      pollIntervalMs: e.OUTBOX_POLL_INTERVAL_MS,
      maxBackoffMs: e.OUTBOX_MAX_BACKOFF_MS,
      claimLeaseMs: e.OUTBOX_CLAIM_LEASE_MS,
    },
    httpClientTimeoutMs: e.HTTP_CLIENT_TIMEOUT_MS,
    awsRegion: e.AWS_REGION,
    awsEndpointUrl: e.AWS_ENDPOINT_URL || undefined,
  };
}
