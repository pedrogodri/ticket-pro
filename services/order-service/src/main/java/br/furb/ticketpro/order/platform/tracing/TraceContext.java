package br.furb.ticketpro.order.platform.tracing;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** W3C traceparent propagation through messages (ADR-0012). */
public final class TraceContext {

  private static final String HEADER = "traceparent";
  private static final W3CTraceContextPropagator PROPAGATOR =
      W3CTraceContextPropagator.getInstance();
  private static final TextMapGetter<Map<String, String>> GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier == null ? null : carrier.get(key);
        }
      };

  private TraceContext() {}

  /** The traceparent of the current span, or empty when there is none. */
  public static Optional<String> inject() {
    Map<String, String> carrier = new HashMap<>();
    PROPAGATOR.inject(Context.current(), carrier, Map::put);
    return Optional.ofNullable(carrier.get(HEADER));
  }

  /**
   * A context carrying the remote span described by {@code traceparent} (root context when empty).
   */
  public static Context extract(String traceparent) {
    if (traceparent == null || traceparent.isEmpty()) {
      return Context.root();
    }
    return PROPAGATOR.extract(Context.root(), Map.of(HEADER, traceparent), GETTER);
  }
}
