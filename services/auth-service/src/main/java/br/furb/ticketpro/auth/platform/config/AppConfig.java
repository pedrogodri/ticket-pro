package br.furb.ticketpro.auth.platform.config;

import br.furb.ticketpro.auth.platform.outbox.OutboxRelayProperties;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Validated runtime configuration read from environment variables (docs/padroes.md §8).
 *
 * @param database API role (RLS applies), from DATABASE_URL
 * @param databaseWorker worker role (relay, consumers, jobs), from DATABASE_WORKER_URL
 * @param topicArn AUTH_TOPIC_ARN
 * @param outbox OUTBOX_* tuning (RFC 0003 §5.1)
 * @param httpClientTimeout HTTP_CLIENT_TIMEOUT_MS
 * @param awsRegion AWS_REGION
 * @param awsEndpointUrl AWS_ENDPOINT_URL (MiniStack), empty to use the SDK default
 */
public record AppConfig(
    String serviceName,
    int port,
    String logLevel,
    DatabaseUrl database,
    DatabaseUrl databaseWorker,
    String topicArn,
    OutboxRelayProperties outbox,
    Duration httpClientTimeout,
    String awsRegion,
    Optional<String> awsEndpointUrl,
    Jwt jwt,
    Argon2 argon2) {

  /** JWT settings (RFC 0002 §5.2–5.4). */
  public record Jwt(
      String secretId,
      String issuer,
      String audience,
      long accessTtlSeconds,
      long refreshTtlSeconds,
      Duration keyRefresh) {}

  /** Argon2id parameters (RFC 0002 §5.1). */
  public record Argon2(int memoryKb, int iterations, int parallelism) {}

  private static final Set<String> LOG_LEVELS = Set.of("debug", "info", "warn", "error");

  /**
   * Builds the configuration, failing fast with the name of the offending variable.
   *
   * @param env variable lookup (Spring Environment or a map)
   * @throws IllegalStateException when a variable is missing or invalid
   */
  public static AppConfig from(Function<String, String> env) {
    String serviceName = required(env, "SERVICE_NAME");
    int port = integer(env, "PORT", null, 1, 65535);
    String logLevel = required(env, "LOG_LEVEL").toLowerCase(Locale.ROOT);
    if (!LOG_LEVELS.contains(logLevel)) {
      throw new IllegalStateException(
          "LOG_LEVEL must be one of debug, info, warn, error; got '" + logLevel + "'");
    }
    return new AppConfig(
        serviceName,
        port,
        logLevel,
        DatabaseUrl.parse(required(env, "DATABASE_URL"), "DATABASE_URL"),
        DatabaseUrl.parse(required(env, "DATABASE_WORKER_URL"), "DATABASE_WORKER_URL"),
        required(env, "AUTH_TOPIC_ARN"),
        new OutboxRelayProperties(
            integer(env, "OUTBOX_BATCH_SIZE", 50, 1, Integer.MAX_VALUE),
            millis(env, "OUTBOX_POLL_INTERVAL_MS", 200),
            millis(env, "OUTBOX_MAX_BACKOFF_MS", 30000),
            millis(env, "OUTBOX_CLAIM_LEASE_MS", 30000)),
        millis(env, "HTTP_CLIENT_TIMEOUT_MS", 3000),
        required(env, "AWS_REGION"),
        Optional.ofNullable(env.apply("AWS_ENDPOINT_URL"))
            .map(String::trim)
            .filter(s -> !s.isEmpty()),
        new Jwt(
            required(env, "JWT_SECRET_ID"),
            optional(env, "JWT_ISSUER", "ticketpro-auth"),
            optional(env, "JWT_AUDIENCE", "ticketpro-api"),
            integer(env, "JWT_ACCESS_TTL_S", 900, 1, Integer.MAX_VALUE),
            integer(env, "JWT_REFRESH_TTL_S", 604800, 1, Integer.MAX_VALUE),
            Duration.ofSeconds(integer(env, "JWT_KEY_REFRESH_S", 300, 1, Integer.MAX_VALUE))),
        new Argon2(
            integer(env, "ARGON2_MEMORY_KB", 19456, 1024, Integer.MAX_VALUE),
            integer(env, "ARGON2_ITERATIONS", 2, 1, 100),
            integer(env, "ARGON2_PARALLELISM", 1, 1, 64)));
  }

  private static String optional(Function<String, String> env, String name, String fallback) {
    String value = env.apply(name);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String required(Function<String, String> env, String name) {
    String value = env.apply(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value.trim();
  }

  private static int integer(
      Function<String, String> env, String name, Integer fallback, int min, int max) {
    String raw = env.apply(name);
    if (raw == null || raw.isBlank()) {
      if (fallback == null) {
        throw new IllegalStateException(name + " is required");
      }
      return fallback;
    }
    try {
      int n = Integer.parseInt(raw.trim());
      if (n < min || n > max) {
        throw new IllegalStateException(
            name + " must be between " + min + " and " + max + ", got " + n);
      }
      return n;
    } catch (NumberFormatException e) {
      throw new IllegalStateException(name + " must be an integer, got '" + raw + "'", e);
    }
  }

  private static Duration millis(Function<String, String> env, String name, int fallback) {
    return Duration.ofMillis(integer(env, name, fallback, 1, Integer.MAX_VALUE));
  }
}
