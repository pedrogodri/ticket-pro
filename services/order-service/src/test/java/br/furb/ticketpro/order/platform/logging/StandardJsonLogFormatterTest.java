package br.furb.ticketpro.order.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.mock.env.MockEnvironment;

/** docs/padroes.md §5: one JSON line with timestamp, level (lowercase), service, message. */
class StandardJsonLogFormatterTest {

  private final StandardJsonLogFormatter formatter =
      new StandardJsonLogFormatter(
          new MockEnvironment().withProperty("spring.application.name", "order-service"));

  private final Logger logger = loggerWithMdc();

  private static Logger loggerWithMdc() {
    LoggerContext context = new LoggerContext();
    context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
    return context.getLogger("br.furb.test");
  }

  private Map<String, Object> format(Level level, String message, Throwable error) {
    LoggingEvent event = new LoggingEvent(Logger.FQCN, logger, level, message, error, null);
    String line = formatter.format(event);
    assertThat(line).endsWith("\n");
    return JsonParserFactory.getJsonParser().parseMap(line);
  }

  @Test
  void emitsStandardKeys() {
    Map<String, Object> line = format(Level.WARN, "http_server_started", null);

    assertThat(line)
        .containsEntry("level", "warn")
        .containsEntry("message", "http_server_started")
        .containsEntry("service", "order-service")
        .doesNotContainKeys("@timestamp", "log", "ecs");
    assertThat(Instant.parse((String) line.get("timestamp"))).isNotNull();
  }

  @Test
  void mapsLevelsToStandardNames() {
    assertThat(format(Level.TRACE, "x", null)).containsEntry("level", "debug");
    assertThat(format(Level.DEBUG, "x", null)).containsEntry("level", "debug");
    assertThat(format(Level.INFO, "x", null)).containsEntry("level", "info");
    assertThat(format(Level.ERROR, "x", null)).containsEntry("level", "error");
  }

  @Test
  void p06CorrelationAndTraceFromMdc() {
    LoggingEvent event =
        new LoggingEvent(Logger.FQCN, logger, Level.INFO, "message_consumed", null, null);
    event.setMDCPropertyMap(Map.of("correlationId", "c-1", "traceId", "t-1", "spanId", "s-1"));
    Map<String, Object> line = JsonParserFactory.getJsonParser().parseMap(formatter.format(event));
    assertThat(line)
        .containsEntry("correlationId", "c-1")
        .containsEntry("traceId", "t-1")
        .containsEntry("spanId", "s-1");

    assertThat(format(Level.INFO, "no_context", null)).doesNotContainKey("correlationId");
  }

  @Test
  void keyValuesBecomeFlatFieldsAndMessageStaysStable() {
    LoggingEvent event =
        new LoggingEvent(Logger.FQCN, logger, Level.INFO, "message_published", null, null);
    event.addKeyValuePair(new org.slf4j.event.KeyValuePair("messageId", "m-1"));
    event.addKeyValuePair(new org.slf4j.event.KeyValuePair("version", 2));
    String json = formatter.format(event);
    Map<String, Object> line = JsonParserFactory.getJsonParser().parseMap(json);
    assertThat(line)
        .containsEntry("message", "message_published")
        .containsEntry("messageId", "m-1")
        .doesNotContainKey("fields");
    assertThat(json).contains("\"version\":2");
  }

  /** p18: booleans stay JSON booleans, as scripts/f3-duplicate.sh greps for. */
  @Test
  void p18BooleanKeyValuesAreTyped() {
    LoggingEvent event =
        new LoggingEvent(Logger.FQCN, logger, Level.INFO, "message_consumed", null, null);
    event.addKeyValuePair(new org.slf4j.event.KeyValuePair("duplicate", true));
    assertThat(formatter.format(event)).contains("\"duplicate\":true");
  }

  @Test
  void includesErrorKindAndMessage() {
    Map<String, Object> line =
        format(Level.ERROR, "server_failed", new IllegalStateException("boom"));

    assertThat(line.get("error"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("kind", "IllegalStateException")
        .containsEntry("message", "boom");
  }
}
