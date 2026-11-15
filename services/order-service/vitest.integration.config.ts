import { defineConfig } from "vitest/config";

// Testcontainers (Postgres + MiniStack) integration tests; files share containers, so they run in one fork.
export default defineConfig({
  test: {
    include: ["tests/**/*.int.test.ts"],
    testTimeout: 60_000,
    hookTimeout: 120_000,
    fileParallelism: false,
  },
});
