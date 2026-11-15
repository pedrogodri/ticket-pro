import { Writable } from "node:stream";
import { describe, expect, it } from "vitest";
import { buildServer } from "../../src/app/server.js";

// docs/padroes.md §5: one JSON line with timestamp, level (lowercase), service, message.
describe("logger schema", () => {
  it("emits timestamp, lowercase level, service and message", async () => {
    const lines: string[] = [];
    const sink = new Writable({
      write(chunk: Buffer, _encoding, done) {
        lines.push(chunk.toString());
        done();
      },
    });
    const app = buildServer(
      { serviceName: "order-service", logLevel: "info" },
      { pingDb: () => Promise.resolve() },
      sink,
    );

    app.log.warn("http_server_started");
    await app.close();

    const line = JSON.parse(lines.find((l) => l.includes("http_server_started")) ?? "{}") as Record<
      string,
      unknown
    >;
    expect(line["level"]).toBe("warn");
    expect(line["message"]).toBe("http_server_started");
    expect(line["service"]).toBe("order-service");
    expect(typeof line["timestamp"]).toBe("string");
    expect(Number.isNaN(Date.parse(String(line["timestamp"])))).toBe(false);
    expect(line).not.toHaveProperty("msg");
    expect(line).not.toHaveProperty("time");
  });
});
