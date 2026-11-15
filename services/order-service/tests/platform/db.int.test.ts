import { Writable } from "node:stream";
import { PostgreSqlContainer } from "@testcontainers/postgresql";
import { expect, it } from "vitest";
import { createPool } from "../../src/platform/db.js";
import { createLogger } from "../../src/platform/logging.js";

// Review finding: a pg.Pool without an "error" listener crashes the process when Postgres restarts
// (idle clients get FATAL 57P01). The service must survive and let readiness report db: down.
it("pool survives the database going away and logs db_client_error", async () => {
  const pg = await new PostgreSqlContainer("postgres:16-alpine").start();
  const lines: string[] = [];
  const logger = createLogger(
    "test",
    "warn",
    new Writable({
      write: (c: Buffer, _e, done) => {
        lines.push(c.toString());
        done();
      },
    }),
  );
  const pool = createPool(pg.getConnectionUri(), logger);
  await pool.query("SELECT 1"); // leaves an idle client in the pool
  await pg.stop();
  await new Promise((r) => setTimeout(r, 1500));
  expect(lines.some((l) => l.includes("db_client_error"))).toBe(true);
  await expect(pool.query("SELECT 1")).rejects.toThrow();
  await pool.end().catch(() => undefined);
}, 120_000);
