package br.furb.ticketpro.inventory.platform.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import br.furb.ticketpro.inventory.platform.support.RecordedSpans;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** ADR-0012 / RFC 0004 §5.9: the messaging spans mirror shared/go (consumer + link, producer). */
class SpansTest {

  private static final String STORED = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @AfterEach
  void noopAgain() {
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  void consumerContinuesTheProducerTraceAndLinksToIt() {
    try (RecordedSpans spans = new RecordedSpans()) {
      Span producer =
          spans.tracer().spanBuilder("publish").setSpanKind(SpanKind.PRODUCER).startSpan();
      String traceparent;
      try (Scope ignored = producer.makeCurrent()) {
        traceparent = TraceContext.inject().orElseThrow();
      }
      producer.end();

      Spans.consumer(traceparent, "PaymentFailed", "queue-url", "msg-1", "inventory-service").end();

      SpanData consumer = spans.only(SpanKind.CONSUMER);
      assertThat(consumer.getName()).isEqualTo("process PaymentFailed");
      assertThat(consumer.getTraceId()).isEqualTo(producer.getSpanContext().getTraceId());
      assertThat(consumer.getParentSpanId()).isEqualTo(producer.getSpanContext().getSpanId());
      assertThat(consumer.getLinks())
          .singleElement()
          .satisfies(l ->
              assertThat(l.getSpanContext().getSpanId())
                  .isEqualTo(producer.getSpanContext().getSpanId()));
      assertThat(consumer.getAttributes().get(AttributeKey.stringKey("messaging.system")))
          .isEqualTo("aws_sqs");
      assertThat(consumer.getAttributes().get(AttributeKey.stringKey("messaging.message.id")))
          .isEqualTo("msg-1");
      assertThat(
              consumer.getAttributes().get(AttributeKey.stringKey("messaging.consumer.group.name")))
          .isEqualTo("inventory-service");
    }
  }

  @Test
  void consumerWithoutTraceparentIsARootWithoutLinks() {
    try (RecordedSpans spans = new RecordedSpans()) {
      Spans.consumer(null, "PaymentFailed", "queue-url", "msg-1", "inventory-service").end();

      SpanData consumer = spans.only(SpanKind.CONSUMER);
      assertThat(consumer.getParentSpanContext().isValid()).isFalse();
      assertThat(consumer.getLinks()).isEmpty();
    }
  }

  @Test
  void producerContinuesTheStoredTraceAndPropagatesItself() {
    try (RecordedSpans spans = new RecordedSpans()) {
      Span producer = Spans.producer(STORED, "OrderCreated", "topic-arn", "msg-2");
      String published;
      try (Scope ignored = producer.makeCurrent()) {
        published = TraceContext.inject().orElseThrow();
      }
      producer.end();

      SpanData span = spans.only(SpanKind.PRODUCER);
      assertThat(span.getName()).isEqualTo("publish OrderCreated");
      assertThat(span.getTraceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
      assertThat(span.getParentSpanId()).isEqualTo("00f067aa0ba902b7");
      assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.system")))
          .isEqualTo("aws_sns");
      assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")))
          .isEqualTo("topic-arn");
      assertThat(published)
          .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-" + span.getSpanId() + "-01");
    }
  }

  /**
   * Background polling (relay, consumer, jobs) runs unsampled so its JDBC and AWS calls do not
   * flood Jaeger, while a message span with an explicit parent is still recorded.
   */
  @Test
  void pollingIsUnsampledButMessageSpansStillRecord() {
    try (RecordedSpans spans = new RecordedSpans()) {
      String traceparent;
      Span producer = spans.tracer().spanBuilder("publish").startSpan();
      try (Scope ignored = producer.makeCurrent()) {
        traceparent = TraceContext.inject().orElseThrow();
      }
      producer.end();
      try (Scope ignored = Spans.unsampled()) {
        Span poll =
            spans.tracer().spanBuilder("UPDATE outbox").setSpanKind(SpanKind.CLIENT).startSpan();
        assertThat(poll.isRecording()).isFalse();
        poll.end();
        Spans.consumer(traceparent, "PaymentFailed", "queue-url", "msg-1", "inventory-service")
            .end();
        Spans.consumer(null, "PaymentFailed", "queue-url", "msg-2", "inventory-service").end();
      }
      assertThat(spans.ended()).noneMatch(s -> s.getKind() == SpanKind.CLIENT);
      assertThat(spans.ended()).filteredOn(s -> s.getKind() == SpanKind.CONSUMER).hasSize(2);
    }
  }

  /** Without the agent the global SDK is a no-op: the stored traceparent is propagated as is. */
  @Test
  void withoutAnSdkTheStoredTraceparentPassesThrough() {
    GlobalOpenTelemetry.resetForTest();
    Span producer = Spans.producer(STORED, "OrderCreated", "topic-arn", "msg-2");
    try (Scope ignored = producer.makeCurrent()) {
      assertThat(TraceContext.inject()).contains(STORED);
    } finally {
      producer.end();
    }
  }
}
