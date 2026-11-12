package br.furb.ticketpro.auth.platform.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import java.time.Instant;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

/**
 * Structured console format following docs/padroes.md §5: one JSON line with timestamp (RFC 3339
 * UTC), level (debug|info|warn|error), service, message and optional error. Enabled by {@code
 * logging.structured.format.console} in application.yml.
 */
public class StandardJsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

  private final JsonWriter<ILoggingEvent> writer;

  public StandardJsonLogFormatter(Environment environment) {
    String service = environment.getProperty("spring.application.name", "auth-service");
    this.writer =
        JsonWriter.<ILoggingEvent>of(
                members -> {
                  members.add("timestamp", ILoggingEvent::getInstant).as(Instant::toString);
                  members.add("level", event -> standardLevel(event.getLevel()));
                  members.add("service", service);
                  members.add("message", ILoggingEvent::getFormattedMessage);
                  members.add("category", ILoggingEvent::getLoggerName);
                  members
                      .add("error", ILoggingEvent::getThrowableProxy)
                      .whenNotNull()
                      .usingMembers(error -> {
                        error.add("kind", proxy -> simpleName(proxy.getClassName()));
                        error.add("message", IThrowableProxy::getMessage);
                      });
                })
            .withNewLineAtEnd();
  }

  @Override
  public String format(ILoggingEvent event) {
    return writer.writeToString(event);
  }

  private static String standardLevel(Level level) {
    if (level.isGreaterOrEqual(Level.ERROR)) {
      return "error";
    }
    if (level.isGreaterOrEqual(Level.WARN)) {
      return "warn";
    }
    if (level.isGreaterOrEqual(Level.INFO)) {
      return "info";
    }
    return "debug";
  }

  private static String simpleName(String className) {
    int dot = className.lastIndexOf('.');
    return dot < 0 ? className : className.substring(dot + 1);
  }
}
