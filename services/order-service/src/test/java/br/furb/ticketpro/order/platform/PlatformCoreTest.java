package br.furb.ticketpro.order.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.furb.ticketpro.order.platform.envelope.ContractValidator;
import br.furb.ticketpro.order.platform.envelope.Envelope;
import br.furb.ticketpro.order.platform.envelope.EnvelopeCodec;
import br.furb.ticketpro.order.platform.envelope.InvalidMessageException;
import br.furb.ticketpro.order.platform.envelope.PoisonMessageException;
import br.furb.ticketpro.order.platform.envelope.UnsupportedVersionException;
import br.furb.ticketpro.order.platform.retry.Backoff;
import br.furb.ticketpro.order.platform.tracing.TraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Platform test matrix P01–P05 (F1 plan); P06 lives in StandardJsonLogFormatterTest. */
class PlatformCoreTest {

  /** The service's vendored contracts (ADR-0017): tests stay inside the service folder. */
  static final Path CONTRACTS = Path.of("contracts");

  private static final Duration BASE = Duration.ofMillis(200);
  private static final Duration CAP = Duration.ofSeconds(30);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void p01DelayIsFullJitterBoundedByCap() {
    assertThat(Backoff.delay(5, BASE, CAP, () -> 0)).isEqualTo(Duration.ZERO);
    for (var c : List.of(Map.entry(0, 200L), Map.entry(3, 1600L), Map.entry(10, 30000L))) {
      long got = Backoff.delay(c.getKey(), BASE, CAP, () -> 0.999999).toMillis();
      assertThat(got).isBetween(c.getValue() - c.getValue() / 1000 - 1, c.getValue());
    }
    assertThat(Backoff.delay(1000, BASE, CAP, () -> 0.999999)).isLessThanOrEqualTo(CAP);
    for (int n = 0; n < 12; n++) {
      Duration limit = Duration.ofMillis(Math.min(CAP.toMillis(), BASE.toMillis() << n));
      for (int i = 0; i < 1000; i++) {
        Duration d = Backoff.delay(n, BASE, CAP, () -> ThreadLocalRandom.current().nextDouble());
        assertThat(d).isBetween(Duration.ZERO, limit);
      }
    }
  }

  @Test
  void p02CreateSerializesExactlySixKeys() {
    Envelope env =
        Envelope.create(
            "c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c", "UserRegistered", 1, Map.of("userId", "u-1"));
    String json = EnvelopeCodec.serialize(env);
    JsonNode node = JSON.readTree(json);
    assertThat(node.propertyNames())
        .containsExactly("messageId", "correlationId", "type", "version", "occurredAt", "payload");
    assertThat(UUID.fromString(env.messageId())).isNotNull();
    assertThat(node.get("occurredAt").asString())
        .matches("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z$");
    assertThat(EnvelopeCodec.unwrap(json)).isEqualTo(env);
  }

  @Test
  void p03UnwrapAcceptsSnsAndRawAndRejectsGarbage() {
    String raw =
        "{\"messageId\":\"0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55\",\"correlationId\":\"c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c\","
            + "\"type\":\"Ping\",\"version\":1,\"occurredAt\":\"2026-11-20T18:30:12.345Z\",\"payload\":{\"a\":1}}";
    String sns =
        "{\"Type\":\"Notification\",\"MessageId\":\"x\",\"Message\":"
            + JSON.writeValueAsString(raw)
            + "}";

    Envelope fromRaw = EnvelopeCodec.unwrap(raw);
    Envelope fromSns = EnvelopeCodec.unwrap(sns);
    assertThat(fromSns).isEqualTo(fromRaw);
    assertThat(fromSns.payload().toString()).isEqualTo("{\"a\":1}");
    for (String body : List.of("{\"Type\":\"Notification\",\"Message\":\"x\"}", "[]", "not json")) {
      assertThatThrownBy(() -> EnvelopeCodec.unwrap(body))
          .isInstanceOf(PoisonMessageException.class);
    }
  }

  @Test
  void p04ValidatorAgainstVendoredContracts() throws IOException {
    ContractValidator validator = new ContractValidator(CONTRACTS);
    try (Stream<Path> files = Files.list(CONTRACTS.resolve("examples"))) {
      List<Path> examples = files.filter(p -> p.toString().endsWith(".json")).toList();
      assertThat(examples).isNotEmpty();
      for (Path f : examples) {
        assertThatCode(() -> validator.validate(Files.readString(f)))
            .as(f.toString())
            .doesNotThrowAnyException();
      }
    }
    // examples/invalid is not vendored: invalid cases are built from a valid example (F1 plan, Task
    // 11).
    String valid = Files.readString(CONTRACTS.resolve("examples/PaymentFailed.v1.json"));
    assertThatThrownBy(() -> validator.validate(valid.replace("\"version\": 1", "\"version\": 3")))
        .isInstanceOf(UnsupportedVersionException.class);
    assertThatThrownBy(() ->
            validator.validate(
                valid.replace("\"type\": \"PaymentFailed\"", "\"type\": \"NoSuchEvent\"")))
        .isInstanceOf(UnsupportedVersionException.class);
    assertThatThrownBy(() -> validator.validate(valid.replace("\"reason\"", "\"cause\"")))
        .isInstanceOf(InvalidMessageException.class);
    assertThatThrownBy(
            () -> validator.validate(valid.replace("0193a6f0-2222-7000-8000-000000000002", "nope")))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void p05TraceparentRoundTrip() {
    SpanContext remote =
        SpanContext.create(
            "4bf92f3577b34da6a3ce929d0e0e4736",
            "00f067aa0ba902b7",
            TraceFlags.getSampled(),
            TraceState.getDefault());
    try (Scope ignored = Context.root().with(Span.wrap(remote)).makeCurrent()) {
      String header = TraceContext.inject().orElseThrow();
      assertThat(header).matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$");
      assertThat(Span.fromContext(TraceContext.extract(header)).getSpanContext().getTraceId())
          .isEqualTo(remote.getTraceId());
    }
    assertThat(TraceContext.inject()).isEmpty();
  }
}
