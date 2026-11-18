import { getEventListeners } from "node:events";
import type { Pool } from "pg";
import pino from "pino";
import { describe, expect, it } from "vitest";
import { createDeadlineScanner } from "../../../src/modules/orders/application/deadlineScanner.js";

describe("deadline scanner loop", () => {
  // Every tick used to leave an abort listener behind (≈3600/hour at 1 s).
  it("does not accumulate abort listeners across ticks and stops on abort", async () => {
    const failingPool = { connect: () => Promise.reject(new Error("db down")) } as unknown as Pool;
    const scanner = createDeadlineScanner(failingPool, {
      topicArn: "arn:t",
      intervalMs: 1,
      batchSize: 10,
    });
    const stop = new AbortController();
    const done = scanner.run(stop.signal, pino({ level: "silent" }));
    await new Promise((r) => setTimeout(r, 60));
    expect(getEventListeners(stop.signal, "abort").length).toBeLessThanOrEqual(1);
    stop.abort();
    await done;
  });
});
