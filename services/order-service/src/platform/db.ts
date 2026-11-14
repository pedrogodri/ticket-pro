import pg from "pg";
import type { Logger } from "./logging.js";

/**
 * A pg pool that survives database restarts (RFC 0003 §5.9): without an "error" listener an idle client
 * hit by FATAL 57P01 raises an uncaught exception and kills the process; readiness reports db: down instead.
 */
export function createPool(connectionString: string, logger: Logger): pg.Pool {
  const pool = new pg.Pool({ connectionString, connectionTimeoutMillis: 2000 });
  pool.on("error", (error) => {
    logger.warn({ error: error.message }, "db_client_error");
  });
  return pool;
}
