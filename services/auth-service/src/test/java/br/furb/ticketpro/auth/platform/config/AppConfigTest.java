package br.furb.ticketpro.auth.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AppConfigTest {

  private static Map<String, String> validEnv() {
    Map<String, String> env = new HashMap<>();
    env.put("SERVICE_NAME", "auth-service");
    env.put("PORT", "8080");
    env.put("LOG_LEVEL", "info");
    env.put("DATABASE_URL", "postgres://auth_app:secret@auth-db:5432/auth");
    env.put("DATABASE_WORKER_URL", "postgres://auth_worker:secret@auth-db:5432/auth");
    env.put("AUTH_TOPIC_ARN", "arn:aws:sns:us-east-1:000000000000:ticketpro-auth");
    env.put("AWS_REGION", "us-east-1");
    env.put("JWT_SECRET_ID", "ticketpro/auth/jwt-signing-key");
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
        "AUTH_TOPIC_ARN",
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
    "DATABASE_URL, mysql://x"
  })
  void invalidValueFailsNamingIt(String name, String value) {
    Map<String, String> env = validEnv();
    env.put(name, value);
    assertThatThrownBy(() -> AppConfig.from(env::get)).hasMessageContaining(name);
  }

  @Test
  void validEnvUsesRfc0003Defaults() {
    AppConfig config = AppConfig.from(validEnv()::get);
    assertThat(config.serviceName()).isEqualTo("auth-service");
    assertThat(config.port()).isEqualTo(8080);
    assertThat(config.logLevel()).isEqualTo("info");
    assertThat(config.database())
        .isEqualTo(new DatabaseUrl("jdbc:postgresql://auth-db:5432/auth", "auth_app", "secret"));
    assertThat(config.databaseWorker().user()).isEqualTo("auth_worker");
    assertThat(config.topicArn()).isEqualTo("arn:aws:sns:us-east-1:000000000000:ticketpro-auth");
    assertThat(config.outbox().batchSize()).isEqualTo(50);
    assertThat(config.outbox().pollInterval()).isEqualTo(Duration.ofMillis(200));
    assertThat(config.outbox().maxBackoff()).isEqualTo(Duration.ofSeconds(30));
    assertThat(config.outbox().claimLease()).isEqualTo(Duration.ofSeconds(30));
    assertThat(config.httpClientTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(config.awsEndpointUrl()).isEmpty();
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
