import { defineConfig } from "vitest/config";

// Unit and contract tests (make test). Integration tests (*.int.test.ts) need Docker: make test-integration.
export default defineConfig({
  test: {
    include: ["tests/**/*.test.ts"],
    exclude: ["tests/**/*.int.test.ts", "node_modules/**"],
  },
});
