import { describe, expect, it } from "vitest";
import { loadConfig } from "../../src/app/config.js";

const validEnv = (): NodeJS.ProcessEnv => ({
  SERVICE_NAME: "order-service",
  PORT: "8080",
  LOG_LEVEL: "info",
  DATABASE_URL: "postgres://orders_app:secret@orders-db:5432/orders",
  DATABASE_WORKER_URL: "postgres://orders_worker:secret@orders-db:5432/orders",
  ORDERS_TOPIC_ARN: "arn:aws:sns:us-east-1:000000000000:ticketpro-orders",
  AWS_REGION: "us-east-1",
});

describe("loadConfig", () => {
  it.each([
    "SERVICE_NAME",
    "PORT",
    "LOG_LEVEL",
    "DATABASE_URL",
    "DATABASE_WORKER_URL",
    "ORDERS_TOPIC_ARN",
    "AWS_REGION",
  ])("fails naming %s when missing", (name) => {
    expect(() => loadConfig({ ...validEnv(), [name]: undefined })).toThrow(new RegExp(name));
  });

  it.each([
    ["PORT", "abc"],
    ["LOG_LEVEL", "verbose"],
    ["OUTBOX_BATCH_SIZE", "0"],
    ["OUTBOX_POLL_INTERVAL_MS", "soon"],
    ["DATABASE_URL", "mysql://x"],
  ])("fails naming %s when invalid (%s)", (name, value) => {
    expect(() => loadConfig({ ...validEnv(), [name]: value })).toThrow(new RegExp(name));
  });

  it("parses valid env with the RFC 0003 defaults", () => {
    expect(loadConfig(validEnv())).toEqual({
      serviceName: "order-service",
      port: 8080,
      logLevel: "info",
      databaseUrl: "postgres://orders_app:secret@orders-db:5432/orders",
      databaseWorkerUrl: "postgres://orders_worker:secret@orders-db:5432/orders",
      topicArn: "arn:aws:sns:us-east-1:000000000000:ticketpro-orders",
      outbox: { batchSize: 50, pollIntervalMs: 200, maxBackoffMs: 30_000, claimLeaseMs: 30_000 },
      httpClientTimeoutMs: 3000,
      awsRegion: "us-east-1",
      awsEndpointUrl: undefined,
    });
  });

  it("applies overrides", () => {
    const config = loadConfig({
      ...validEnv(),
      OUTBOX_BATCH_SIZE: "10",
      HTTP_CLIENT_TIMEOUT_MS: "500",
      AWS_ENDPOINT_URL: "http://ministack:4566",
    });
    expect(config.outbox.batchSize).toBe(10);
    expect(config.httpClientTimeoutMs).toBe(500);
    expect(config.awsEndpointUrl).toBe("http://ministack:4566");
  });
});
