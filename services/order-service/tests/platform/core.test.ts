import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { Writable } from "node:stream";
import { context, trace, TraceFlags } from "@opentelemetry/api";
import { describe, expect, it } from "vitest";
import {
  createEnvelope,
  InvalidMessageError,
  PoisonMessageError,
  serializeEnvelope,
  unwrap,
  UnsupportedVersionError,
} from "../../src/platform/envelope.js";
import { createLogger, withLogContext } from "../../src/platform/logging.js";
import { delay } from "../../src/platform/retry.js";
import { extract, inject } from "../../src/platform/tracing.js";
import { createValidator } from "../../src/platform/validator.js";

/** The service's vendored contracts (ADR-0017): tests stay inside the service folder. */
export const CONTRACTS = "contracts";

describe("platform core (P01–P06)", () => {
  it("P01 delay is full jitter bounded by the cap", () => {
    expect(delay(5, 200, 30_000, () => 0)).toBe(0);
    for (const [attempt, want] of [
      [0, 200],
      [3, 1600],
      [10, 30_000],
    ] as const) {
      const got = delay(attempt, 200, 30_000, () => 0.999999);
      expect(got).toBeLessThanOrEqual(want);
      expect(got).toBeGreaterThanOrEqual(want - want / 1000);
    }
    expect(delay(1000, 200, 30_000, () => 0.999999)).toBeLessThanOrEqual(30_000);
    for (let n = 0; n < 12; n++) {
      const limit = Math.min(30_000, 200 * 2 ** n);
      for (let i = 0; i < 1000; i++) {
        const d = delay(n, 200, 30_000, Math.random);
        expect(d).toBeGreaterThanOrEqual(0);
        expect(d).toBeLessThanOrEqual(limit);
      }
    }
  });

  it("P02 createEnvelope serializes exactly six keys", () => {
    const env = createEnvelope("c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c", "OrderCreated", 1, {
      orderId: "o-1",
    });
    const json = serializeEnvelope(env);
    const parsed = JSON.parse(json) as Record<string, unknown>;
    expect(Object.keys(parsed)).toEqual([
      "messageId",
      "correlationId",
      "type",
      "version",
      "occurredAt",
      "payload",
    ]);
    expect(env.messageId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(parsed["occurredAt"]).toMatch(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/);
    expect(unwrap(json)).toEqual(env);
  });

  it("P03 unwrap accepts SNS and raw bodies and rejects garbage", () => {
    const raw =
      '{"messageId":"0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55","correlationId":"c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c","type":"Ping","version":1,"occurredAt":"2026-11-20T18:30:12.345Z","payload":{"a":1}}';
    const sns = JSON.stringify({ Type: "Notification", MessageId: "x", Message: raw });
    expect(unwrap(sns)).toEqual(unwrap(raw));
    expect(unwrap(sns).payload).toEqual({ a: 1 });
    for (const body of ['{"Type":"Notification","Message":"x"}', "[]", "not json"]) {
      expect(() => unwrap(body)).toThrow(PoisonMessageError);
    }
  });

  it("P04 validator against the vendored contracts", async () => {
    const validator = await createValidator(CONTRACTS);
    const examples = readdirSync(join(CONTRACTS, "examples")).filter((f) => f.endsWith(".json"));
    expect(examples.length).toBeGreaterThan(0);
    for (const f of examples) {
      expect(() => {
        validator.validate(readFileSync(join(CONTRACTS, "examples", f), "utf8"));
      }, f).not.toThrow();
    }
    // examples/invalid is not vendored: invalid cases are built from a valid example (F1 plan, Task 11).
    const valid = readFileSync(join(CONTRACTS, "examples", "PaymentFailed.v1.json"), "utf8");
    expect(() => {
      validator.validate(valid.replace('"version": 1', '"version": 3'));
    }).toThrow(UnsupportedVersionError);
    expect(() => {
      validator.validate(valid.replace('"type": "PaymentFailed"', '"type": "NoSuchEvent"'));
    }).toThrow(UnsupportedVersionError);
    expect(() => {
      validator.validate(valid.replace('"reason"', '"cause"'));
    }).toThrow(InvalidMessageError);
  });

  it("P05 traceparent round trip", () => {
    const spanContext = {
      traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
      spanId: "00f067aa0ba902b7",
      traceFlags: TraceFlags.SAMPLED,
    };
    const active = trace.setSpanContext(context.active(), spanContext);
    const header = inject(active);
    expect(header).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
    expect(trace.getSpanContext(extract(header))?.traceId).toBe(spanContext.traceId);
    expect(inject(context.active())).toBeUndefined();
  });

  it("P06 standard keys plus correlationId from the log context", () => {
    const lines: string[] = [];
    const sink = new Writable({
      write(chunk: Buffer, _encoding, done) {
        lines.push(chunk.toString());
        done();
      },
    });
    const logger = createLogger("order-service", "info", sink);
    withLogContext({ correlationId: "c-1" }, () => {
      logger.info({ messageId: "m-1" }, "message_consumed");
    });
    logger.info("no_context");

    const first = JSON.parse(lines[0] ?? "{}") as Record<string, unknown>;
    expect(first).toMatchObject({
      level: "info",
      service: "order-service",
      message: "message_consumed",
      correlationId: "c-1",
      messageId: "m-1",
    });
    expect(Number.isNaN(Date.parse(String(first["timestamp"])))).toBe(false);
    expect(lines[1]).not.toContain("correlationId");
  });
});
