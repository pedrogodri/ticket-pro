import { describe, expect, it } from "vitest";
import { loadConfig } from "../../src/app/config.js";

describe("loadConfig", () => {
  it("fails naming PORT when missing", () => {
    expect(() => loadConfig({ SERVICE_NAME: "order-service", LOG_LEVEL: "info" })).toThrow(/PORT/);
  });

  it("fails naming PORT when not a number", () => {
    expect(() =>
      loadConfig({ SERVICE_NAME: "order-service", PORT: "abc", LOG_LEVEL: "info" }),
    ).toThrow(/PORT/);
  });

  it("fails naming SERVICE_NAME when missing", () => {
    expect(() => loadConfig({ PORT: "8080", LOG_LEVEL: "info" })).toThrow(/SERVICE_NAME/);
  });

  it("rejects invalid LOG_LEVEL", () => {
    expect(() =>
      loadConfig({ SERVICE_NAME: "order-service", PORT: "8080", LOG_LEVEL: "verbose" }),
    ).toThrow(/LOG_LEVEL/);
  });

  it("parses valid env", () => {
    expect(loadConfig({ SERVICE_NAME: "order-service", PORT: "8080", LOG_LEVEL: "info" })).toEqual({
      serviceName: "order-service",
      port: 8080,
      logLevel: "info",
    });
  });
});
