package br.furb.ingressoja.auth.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AppConfigTest {

  @Test
  void missingPortFailsNamingVariable() {
    assertThatThrownBy(
            () -> AppConfig.from(Map.of("SERVICE_NAME", "auth-service", "LOG_LEVEL", "info")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PORT");
  }

  @Test
  void invalidPortFailsNamingVariable() {
    assertThatThrownBy(() ->
            AppConfig.from(
                Map.of("SERVICE_NAME", "auth-service", "PORT", "abc", "LOG_LEVEL", "info")))
        .hasMessageContaining("PORT");
  }

  @Test
  void missingServiceNameFailsNamingVariable() {
    assertThatThrownBy(() -> AppConfig.from(Map.of("PORT", "8080", "LOG_LEVEL", "info")))
        .hasMessageContaining("SERVICE_NAME");
  }

  @Test
  void invalidLogLevelFailsNamingVariable() {
    assertThatThrownBy(() ->
            AppConfig.from(
                Map.of("SERVICE_NAME", "auth-service", "PORT", "8080", "LOG_LEVEL", "verbose")))
        .hasMessageContaining("LOG_LEVEL");
  }

  @Test
  void validEnvBuildsConfig() {
    AppConfig config =
        AppConfig.from(Map.of("SERVICE_NAME", "auth-service", "PORT", "8080", "LOG_LEVEL", "info"));

    assertThat(config).isEqualTo(new AppConfig("auth-service", 8080, "info"));
  }
}
