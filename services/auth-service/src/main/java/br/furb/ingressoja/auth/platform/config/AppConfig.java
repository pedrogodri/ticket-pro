package br.furb.ingressoja.auth.platform.config;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validated runtime configuration read from environment variables (docs/padroes.md §8).
 *
 * @param serviceName value of SERVICE_NAME
 * @param port value of PORT (1-65535)
 * @param logLevel value of LOG_LEVEL (debug, info, warn, error)
 */
public record AppConfig(String serviceName, int port, String logLevel) {

  private static final Set<String> LOG_LEVELS = Set.of("debug", "info", "warn", "error");

  /**
   * Builds the configuration, failing fast with the name of the offending variable.
   *
   * @param env environment variables
   * @return validated configuration
   * @throws IllegalStateException when a variable is missing or invalid
   */
  public static AppConfig from(Map<String, String> env) {
    String serviceName = required(env, "SERVICE_NAME");

    String rawPort = required(env, "PORT");
    int port;
    try {
      port = Integer.parseInt(rawPort);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("PORT must be an integer, got '" + rawPort + "'", e);
    }
    if (port < 1 || port > 65535) {
      throw new IllegalStateException("PORT must be between 1 and 65535, got " + port);
    }

    String logLevel = required(env, "LOG_LEVEL").toLowerCase(Locale.ROOT);
    if (!LOG_LEVELS.contains(logLevel)) {
      throw new IllegalStateException(
          "LOG_LEVEL must be one of debug, info, warn, error; got '" + logLevel + "'");
    }

    return new AppConfig(serviceName, port, logLevel);
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value.trim();
  }
}
