package br.furb.ticketpro.order.platform.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Messaging spans, the same as shared/go (ADR-0012, RFC 0004 §5.9): one consumer span per message,
 * in the producer's trace and linked to it, and one producer span per relay publication. They use
 * the global OpenTelemetry: the OTel Java agent's SDK in the container, a no-op without it (then
 * the stored traceparent passes through unchanged). The caller makes the span current and ends it.
 */
public final class Spans {

  private static final String SCOPE = "br.furb.ticketpro.platform";

  private Spans() {}

  /** "process {type}" (CONSUMER), child of {@code traceparent} and linked to it; root when null. */
  public static Span consumer(
      String traceparent, String type, String queueUrl, String messageId, String consumerName) {
    Context parent = TraceContext.extract(traceparent);
    SpanBuilder builder =
        tracer()
            .spanBuilder("process " + type)
            .setParent(parent)
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute("messaging.system", "aws_sqs")
            .setAttribute("messaging.destination.name", queueUrl)
            .setAttribute("messaging.message.id", messageId)
            .setAttribute("messaging.consumer.group.name", consumerName);
    SpanContext remote = Span.fromContext(parent).getSpanContext();
    if (remote.isValid()) {
      builder.addLink(remote);
    }
    return builder.startSpan();
  }

  /** "publish {type}" (PRODUCER), child of the traceparent stored with the outbox row. */
  public static Span producer(String traceparent, String type, String topicArn, String messageId) {
    return tracer()
        .spanBuilder("publish " + type)
        .setParent(TraceContext.extract(traceparent))
        .setSpanKind(SpanKind.PRODUCER)
        .setAttribute("messaging.system", "aws_sns")
        .setAttribute("messaging.destination.name", topicArn)
        .setAttribute("messaging.message.id", messageId)
        .startSpan();
  }

  /**
   * Makes an unsampled span current for a background loop (relay, consumer polling, jobs). With the
   * agent's parent-based sampler every span created under it is dropped, so polling queries and
   * long polls do not flood Jaeger; {@link #consumer} and {@link #producer} set their parent
   * explicitly and are still recorded.
   */
  public static Scope unsampled() {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    SpanContext quiet =
        SpanContext.create(
            TraceId.fromLongs(r.nextLong(), r.nextLong() | 1),
            SpanId.fromLong(r.nextLong() | 1),
            TraceFlags.getDefault(),
            TraceState.getDefault());
    return Context.root().with(Span.wrap(quiet)).makeCurrent();
  }

  // Looked up on every call: the agent (or a test) may install the global SDK after class init.
  private static Tracer tracer() {
    return GlobalOpenTelemetry.getTracer(SCOPE);
  }
}
