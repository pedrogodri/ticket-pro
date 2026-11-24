package br.furb.ticketpro.order.platform.support;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;

/**
 * Installs an in-memory OpenTelemetry SDK as the global one, standing in for the agent's SDK, and
 * restores the no-op global on close.
 */
public final class RecordedSpans implements AutoCloseable {

  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private final SdkTracerProvider provider;

  public RecordedSpans() {
    GlobalOpenTelemetry.resetForTest();
    provider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
  }

  /** A tracer for the test's own spans (the "upstream" request or producer). */
  public Tracer tracer() {
    return provider.get("test");
  }

  /** Every ended span, in end order. */
  public List<SpanData> ended() {
    return exporter.getFinishedSpanItems();
  }

  /** The single ended span of this kind. */
  public SpanData only(SpanKind kind) {
    List<SpanData> spans =
        exporter.getFinishedSpanItems().stream().filter(s -> s.getKind() == kind).toList();
    if (spans.size() != 1) {
      throw new AssertionError("expected one " + kind + " span, got " + spans);
    }
    return spans.get(0);
  }

  @Override
  public void close() {
    provider.close();
    GlobalOpenTelemetry.resetForTest();
  }
}
