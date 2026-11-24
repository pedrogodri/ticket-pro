package br.furb.ticketpro.order.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AppConfigTest {

  static Map<String, String> validEnv() {
    Map<String, String> env = new HashMap<>();
    env.put("SERVICE_NAME", "order-service");
    env.put("PORT", "8080");
    env.put("LOG_LEVEL", "info");
    env.put("DATABASE_URL", "postgres://orders_app:secret@orders-db:5432/orders");
    env.put("DATABASE_WORKER_URL", "postgres://orders_worker:secret@orders-db:5432/orders");
    env.put("ORDERS_TOPIC_ARN", "arn:aws:sns:us-east-1:000000000000:ticketpro-orders");
    env.put("QUEUE_URL", "http://ministack:4566/000000000000/ticketpro-order-service");
    env.put("CONTRACTS_DIR", "/app/contracts");
    env.put("AWS_REGION", "us-east-1");
    return env;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SERVICE_NAME",
        "PORT",
        "LOG_LEVEL",
        "DATABASE_URL",
        "DATABASE_WORKER_URL",
        "ORDERS_TOPIC_ARN",
        "QUEUE_URL",
        "CONTRACTS_DIR",
        "AWS_REGION"
      })
  void missingRequiredVariableFailsNamingIt(String name) {
    Map<String, String> env = validEnv();
    env.remove(name);
    assertThatThrownBy(() -> AppConfig.from(env::get))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(name);
  }

  @ParameterizedTest
  @CsvSource({
    "PORT, abc",
    "LOG_LEVEL, verbose",
    "OUTBOX_BATCH_SIZE, 0",
    "OUTBOX_POLL_INTERVAL_MS, soon",
    "DATABASE_URL, mysql://x",
    "ORDER_MAX_QUANTITY, 7",
    "ORDER_MAX_QUANTITY, 0",
    "ORDER_CREATED_VERSION, 3",
    "CONSUMER_CONCURRENCY, 0",
    "SQS_WAIT_TIME_S, 21",
    "OUTBOX_RETENTION_H, 0",
    "PROCESSED_MESSAGES_RETENTION_D, 0",
    "RETENTION_INTERVAL_MS, 0",
    "RETENTION_BATCH_SIZE, 0"
  })
  void invalidValueFailsNamingIt(String name, String value) {
    Map<String, String> env = validEnv();
    env.put(name, value);
    assertThatThrownBy(() -> AppConfig.from(env::get)).hasMessageContaining(name);
  }

  @Test
  void validEnvUsesRfcDefaults() {
    AppConfig config = AppConfig.from(validEnv()::get);
    assertThat(config.serviceName()).isEqualTo("order-service");
    assertThat(config.database())
        .isEqualTo(
            new DatabaseUrl("jdbc:postgresql://orders-db:5432/orders", "orders_app", "secret"));
    assertThat(config.databaseWorker().user()).isEqualTo("orders_worker");
    assertThat(config.topicArn()).endsWith(":ticketpro-orders");
    assertThat(config.outbox().batchSize()).isEqualTo(50);
    assertThat(config.outbox().pollInterval()).isEqualTo(Duration.ofMillis(200));
    assertThat(config.httpClientTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(config.awsEndpointUrl()).isEmpty();
    assertThat(config.consumer())
        .isEqualTo(
            new AppConfig.Consumer(
                "http://ministack:4566/000000000000/ticketpro-order-service", 4, 10));
    assertThat(config.contractsDir()).isEqualTo(Path.of("/app/contracts"));
    assertThat(config.shutdownTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(config.orders())
        .isEqualTo(
            new AppConfig.Orders(Duration.ofMillis(15000), Duration.ofMillis(1000), 50, 6, 2));
    assertThat(config.retention())
        .isEqualTo(
            new AppConfig.Retention(
                Duration.ofHours(24), Duration.ofDays(7), Duration.ofMillis(60000), 500));
  }

  /** RFC 0003 decision 4: TTL 40000 with timeout 15000 and margin 30000 must not boot. */
  @Test
  void reservationShorterThanDeadlinePlusMarginFailsNamingAllThree() {
    Map<String, String> env = validEnv();
    env.put("RESERVATION_TTL_MS", "40000");
    env.put("ORDER_PAYMENT_TIMEOUT_MS", "15000");
    env.put("RESERVATION_SAFETY_MARGIN_MS", "30000");
    assertThatThrownBy(() -> AppConfig.from(env::get))
        .hasMessageContaining("RESERVATION_TTL_MS")
        .hasMessageContaining("ORDER_PAYMENT_TIMEOUT_MS")
        .hasMessageContaining("RESERVATION_SAFETY_MARGIN_MS");

    env.put("RESERVATION_TTL_MS", "45000");
    assertThat(AppConfig.from(env::get).orders().paymentTimeout())
        .isEqualTo(Duration.ofMillis(15000));
  }

  @ParameterizedTest
  @CsvSource({
    "postgres://u:p@h:5432/d, jdbc:postgresql://h:5432/d, u, p",
    "postgresql://u:p%40ss@h/d?sslmode=disable, jdbc:postgresql://h:5432/d, u, p@ss"
  })
  void postgresUrlIsConvertedToJdbc(String url, String jdbc, String user, String password) {
    assertThat(DatabaseUrl.parse(url, "DATABASE_URL"))
        .isEqualTo(new DatabaseUrl(jdbc, user, password));
  }
}
