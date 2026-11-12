import { z } from "zod";

const logLevels = ["debug", "info", "warn", "error"] as const;

export type LogLevel = (typeof logLevels)[number];

export interface Config {
  serviceName: string;
  port: number;
  logLevel: LogLevel;
}

const envSchema = z.object({
  SERVICE_NAME: z.string().trim().min(1, "SERVICE_NAME is required"),
  PORT: z.coerce
    .number({ error: "PORT must be an integer between 1 and 65535" })
    .int("PORT must be an integer between 1 and 65535")
    .min(1, "PORT must be an integer between 1 and 65535")
    .max(65535, "PORT must be an integer between 1 and 65535"),
  LOG_LEVEL: z.enum(logLevels, { error: "LOG_LEVEL must be one of debug, info, warn, error" }),
});

/**
 * Reads and validates configuration from environment variables (docs/padroes.md §8).
 * Throws an Error whose message names every offending variable.
 */
export function loadConfig(env: NodeJS.ProcessEnv): Config {
  const parsed = envSchema.safeParse({
    SERVICE_NAME: env["SERVICE_NAME"] ?? "",
    PORT: env["PORT"] ?? "",
    LOG_LEVEL: env["LOG_LEVEL"]?.toLowerCase() ?? "",
  });
  if (!parsed.success) {
    const problems = parsed.error.issues.map(
      (issue) => `${issue.path.join(".")}: ${issue.message}`,
    );
    throw new Error(`Invalid configuration: ${problems.join("; ")}`);
  }
  return {
    serviceName: parsed.data.SERVICE_NAME,
    port: parsed.data.PORT,
    logLevel: parsed.data.LOG_LEVEL,
  };
}
