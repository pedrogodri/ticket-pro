package br.furb.ticketpro.inventory.platform.config;

import br.furb.ticketpro.inventory.platform.outbox.OutboxRelayProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Validated runtime configuration read from environment variables (docs/padroes.md §8, RFC 0004
 * §5.8, RFC 0006 §5.8).
 *
 * @param database API role (RLS applies), from DATABASE_URL
 * @param databaseWorker worker role (relay, consumers, jobs), from DATABASE_WORKER_URL
 * @param topicArn INVENTORY_TOPIC_ARN
 * @param outbox OUTBOX_* tuning (RFC 0003 §5.1)
 * @param httpClientTimeout HTTP_CLIENT_TIMEOUT_MS
 * @param awsRegion AWS_REGION
 * @param awsEndpointUrl AWS_ENDPOINT_URL (MiniStack), empty to use the SDK default
 * @param consumer QUEUE_URL, CONSUMER_CONCURRENCY, SQS_WAIT_TIME_S
 * @param contractsDir CONTRACTS_DIR (vendored contracts/ copied into the image)
 * @param shutdownTimeout SHUTDOWN_TIMEOUT_S: how long in-flight messages may take on stop
 * @param reservations reservation TTL and expiry job tuning (RESERVATION_*)
 * @param retention outbox and processed_messages cleanup (ADR-0005, ADR-0006)
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
    Consumer consumer,
    Path contractsDir,
    Duration shutdownTimeout,
    Reservations reservations,
    Retention retention) {

  /** SQS consumer settings (RFC 0003 §5.1). */
  public record Consumer(String queueUrl, int concurrency, int waitTimeSeconds) {}

  /**
   * Reservation settings (RFC 0006 §5.7, §5.8).
   *
   * @param ttl RESERVATION_TTL_MS: how long a PENDING reservation holds stock
   * @param expiryScanInterval RESERVATION_EXPIRY_SCAN_INTERVAL_MS
   * @param expiryBatchSize RESERVATION_EXPIRY_BATCH_SIZE
   * @param expiryBatchPause RESERVATION_EXPIRY_BATCH_PAUSE_MS between full batches
   */
  public record Reservations(
      Duration ttl, Duration expiryScanInterval, int expiryBatchSize, Duration expiryBatchPause) {}

  /**
   * Retention job settings (RFC 0004 §5.7).
   *
   * @param outbox OUTBOX_RETENTION_H: published outbox rows older than this are deleted
   * @param processedMessages PROCESSED_MESSAGES_RETENTION_D: well above the queue retention
   * @param interval RETENTION_INTERVAL_MS between runs when there is nothing left to purge
   * @param batchSize RETENTION_BATCH_SIZE: rows deleted per table in one transaction
   */
  public record Retention(
      Duration outbox, Duration processedMessages, Duration interval, int batchSize) {}

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
    Reservations reservations =
        new Reservations(
            millis(env, "RESERVATION_TTL_MS", 60000),
            millis(env, "RESERVATION_EXPIRY_SCAN_INTERVAL_MS", 1000),
            integer(env, "RESERVATION_EXPIRY_BATCH_SIZE", 50, 1, 10000),
            millis(env, "RESERVATION_EXPIRY_BATCH_PAUSE_MS", 50));
    checkReservationCoversDeadline(env, reservations.ttl());
    return new AppConfig(
        serviceName,
        port,
        logLevel,
        DatabaseUrl.parse(required(env, "DATABASE_URL"), "DATABASE_URL"),
        DatabaseUrl.parse(required(env, "DATABASE_WORKER_URL"), "DATABASE_WORKER_URL"),
        required(env, "INVENTORY_TOPIC_ARN"),
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
        new Consumer(
            required(env, "QUEUE_URL"),
            integer(env, "CONSUMER_CONCURRENCY", 4, 1, 10),
            integer(env, "SQS_WAIT_TIME_S", 10, 0, 20)),
        Path.of(required(env, "CONTRACTS_DIR")),
        Duration.ofSeconds(integer(env, "SHUTDOWN_TIMEOUT_S", 30, 1, 600)),
        reservations,
        new Retention(
            Duration.ofHours(integer(env, "OUTBOX_RETENTION_H", 24, 1, 8760)),
            Duration.ofDays(integer(env, "PROCESSED_MESSAGES_RETENTION_D", 7, 1, 365)),
            millis(env, "RETENTION_INTERVAL_MS", 60000),
            integer(env, "RETENTION_BATCH_SIZE", 500, 1, 10000)));
  }

  /**
   * RFC 0003 decision 4: the reservation must outlive the payment deadline plus a margin, or an
   * approved payment could find its stock already released.
   */
  private static void checkReservationCoversDeadline(Function<String, String> env, Duration ttl) {
    Duration paymentTimeout = millis(env, "ORDER_PAYMENT_TIMEOUT_MS", 15000);
    Duration margin = millis(env, "RESERVATION_SAFETY_MARGIN_MS", 30000);
    if (ttl.compareTo(paymentTimeout.plus(margin)) < 0) {
      throw new IllegalStateException(
          "RESERVATION_TTL_MS ("
              + ttl.toMillis()
              + ") must be >= ORDER_PAYMENT_TIMEOUT_MS ("
              + paymentTimeout.toMillis()
              + ") + RESERVATION_SAFETY_MARGIN_MS ("
              + margin.toMillis()
              + ")");
    }
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
