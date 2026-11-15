import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { PostgreSqlContainer, type StartedPostgreSqlContainer } from "@testcontainers/postgresql";
import pg from "pg";

/**
 * Postgres 16 with this service's migrations/ applied in order, as Flyway does (placeholders filled with
 * test passwords). appPool connects as orders_app, so RLS is exercised for real (padroes §6.1).
 */
export interface TestDatabase {
  ownerPool: pg.Pool;
  appPool: pg.Pool;
  workerPool: pg.Pool;
  stop(): Promise<void>;
}

export async function startDatabase(): Promise<TestDatabase> {
  const container: StartedPostgreSqlContainer = await new PostgreSqlContainer("postgres:16-alpine")
    .withDatabase("orders")
    .start();
  const ownerPool = new pg.Pool({ connectionString: container.getConnectionUri() });
  const files = readdirSync("migrations")
    .filter((f) => /^V\d+__.*\.sql$/.test(f))
    .sort();
  for (const f of files) {
    const sql = readFileSync(join("migrations", f), "utf8")
      .replaceAll("${app_password}", "app_test")
      .replaceAll("${worker_password}", "worker_test");
    await ownerPool.query(sql);
  }
  const uri = (user: string, password: string) =>
    `postgres://${user}:${password}@${container.getHost()}:${String(container.getMappedPort(5432))}/orders`;
  const appPool = new pg.Pool({ connectionString: uri("orders_app", "app_test") });
  const workerPool = new pg.Pool({ connectionString: uri("orders_worker", "worker_test") });
  return {
    ownerPool,
    appPool,
    workerPool,
    stop: async () => {
      await Promise.all([ownerPool.end(), appPool.end(), workerPool.end()]);
      await container.stop();
    },
  };
}
