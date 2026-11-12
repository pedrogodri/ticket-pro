package br.furb.ticketpro.auth.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.furb.ticketpro.auth.platform.envelope.ContractValidator;
import br.furb.ticketpro.auth.platform.envelope.Envelope;
import br.furb.ticketpro.auth.platform.envelope.EnvelopeCodec;
import br.furb.ticketpro.auth.platform.envelope.StaleEventException;
import br.furb.ticketpro.auth.platform.idempotency.ProcessedMessages;
import br.furb.ticketpro.auth.platform.messaging.ConsumeOutcome;
import br.furb.ticketpro.auth.platform.messaging.MessageHandler;
import br.furb.ticketpro.auth.platform.messaging.SnsOutboxPublisher;
import br.furb.ticketpro.auth.platform.messaging.SqsConsumer;
import br.furb.ticketpro.auth.platform.messaging.SqsConsumerProperties;
import br.furb.ticketpro.auth.platform.outbox.OutboxPublisher;
import br.furb.ticketpro.auth.platform.outbox.OutboxRelay;
import br.furb.ticketpro.auth.platform.outbox.OutboxRelayProperties;
import br.furb.ticketpro.auth.platform.outbox.OutboxWriter;
import br.furb.ticketpro.auth.platform.support.Containers;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Platform test matrix P07–P15 (F1 plan) against Testcontainers Postgres + MiniStack. */
class PlatformIT {

  private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:ticketpro-test";
  private static final String MSG_ID = "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a55";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static Envelope newEnvelope() {
    return Envelope.create("c0a8012e-5f7d-4a8e-9b1c-2d3e4f5a6b7c", "Ping", 1, Map.of("n", 1));
  }

  private static void enqueue(DataSource ds, String topic, Envelope env, boolean commit)
      throws Exception {
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      OutboxWriter.enqueue(c, topic, env);
      if (commit) {
        c.commit();
      } else {
        c.rollback();
      }
    }
  }

  private static OutboxRelay relay(DataSource ds, OutboxPublisher publisher, int batchSize) {
    return new OutboxRelay(
        ds,
        publisher,
        new OutboxRelayProperties(
            batchSize, Duration.ofMillis(50), Duration.ofSeconds(1), Duration.ofSeconds(30)));
  }

  private static Scope activeSpan() {
    SpanContext sc =
        SpanContext.create(
            "4bf92f3577b34da6a3ce929d0e0e4736",
            "00f067aa0ba902b7",
            TraceFlags.getSampled(),
            TraceState.getDefault());
    return Context.root().with(Span.wrap(sc)).makeCurrent();
  }

  @Test
  void p07RecordIsFreshOncePerConsumerAndMessage() throws Exception {
    DataSource ds = Containers.postgres();
    UUID id = UUID.fromString(MSG_ID);
    record Rec(DataSource ds, UUID id) {
      boolean run(String consumer) throws Exception {
        try (Connection c = ds.getConnection()) {
          c.setAutoCommit(false);
          boolean fresh = ProcessedMessages.record(c, consumer, id);
          c.commit();
          return fresh;
        }
      }
    }
    Rec rec = new Rec(ds, id);
    assertThat(rec.run("c1")).isTrue();
    assertThat(rec.run("c1")).isFalse();
    assertThat(rec.run("c2")).isTrue();
  }

  @Test
  void p08EnqueueIsPartOfTheCallerTransaction() throws Exception {
    DataSource ds = Containers.postgres();
    Envelope committed = newEnvelope();
    enqueue(ds, TOPIC_ARN, committed, true);
    enqueue(ds, TOPIC_ARN, newEnvelope(), false);
    assertThat(Containers.count(ds, "SELECT count(*) FROM outbox")).isEqualTo(1);
    assertThat(
            Containers.count(
                ds,
                "SELECT count(*) FROM outbox WHERE message_id = ? AND type = 'Ping' AND version = 1"
                    + " AND correlation_id = ? AND payload = '{\"n\": 1}'::jsonb AND traceparent IS NULL",
                UUID.fromString(committed.messageId()),
                UUID.fromString(committed.correlationId())))
        .isEqualTo(1);

    Envelope traced = newEnvelope();
    try (Scope ignored = activeSpan()) {
      enqueue(ds, TOPIC_ARN, traced, true);
    }
    assertThat(
            Containers.count(
                ds,
                "SELECT count(*) FROM outbox WHERE message_id = ? AND traceparent LIKE '00-%'",
                UUID.fromString(traced.messageId())))
        .isEqualTo(1);
  }

  @Test
  void p12RelayAccumulatesWhileBrokerIsDownAndDrainsAfter() throws Exception {
    DataSource ds = Containers.postgres();
    try (SqsClient sqs = Containers.sqs();
        SnsClient sns = Containers.sns()) {
      String queue = Containers.queue(sqs, "relay", 3)[0];
      String topic = Containers.topic(sns, sqs, "relay", queue);
      List<Envelope> envs = List.of(newEnvelope(), newEnvelope(), newEnvelope());
      try (Scope ignored = activeSpan()) {
        enqueue(ds, topic, envs.get(0), true);
      }
      enqueue(ds, topic, envs.get(1), true);
      enqueue(ds, topic, envs.get(2), true);

      OutboxRelay relay = relay(ds, new SnsOutboxPublisher(sns), 10);
      Containers.pause();
      try {
        assertThatThrownBy(relay::runOnce).isInstanceOf(RuntimeException.class);
        assertThat(relay.brokerUp()).isFalse();
        assertThat(Containers.count(ds, "SELECT count(*) FROM outbox WHERE published_at IS NULL"))
            .isEqualTo(3);
        assertThat(Containers.count(ds, "SELECT coalesce(sum(attempts),0) FROM outbox"))
            .isGreaterThanOrEqualTo(1);
      } finally {
        Containers.unpause();
      }

      AtomicBoolean stop = new AtomicBoolean();
      CompletableFuture<Void> run = CompletableFuture.runAsync(() -> relay.run(stop::get));
      Instant deadline = Instant.now().plusSeconds(30);
      while (Containers.count(ds, "SELECT count(*) FROM outbox WHERE published_at IS NULL") > 0) {
        assertThat(Instant.now()).as("relay drains within 30s").isBefore(deadline);
        Thread.sleep(100);
      }
      stop.set(true);
      run.get();
      assertThat(relay.brokerUp()).isTrue();

      Map<String, Message> got = new HashMap<>();
      while (got.size() < 3 && Instant.now().isBefore(deadline.plusSeconds(10))) {
        for (Message m :
            sqs.receiveMessage(b ->
                    b.queueUrl(queue)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(1)
                        .messageAttributeNames("All"))
                .messages()) {
          got.put(EnvelopeCodec.unwrap(m.body()).messageId(), m);
        }
      }
      for (int i = 0; i < envs.size(); i++) {
        Message m = got.get(envs.get(i).messageId());
        assertThat(m).as("message %d delivered", i).isNotNull();
        assertThat(m.messageAttributes().get("type").stringValue()).isEqualTo("Ping");
        assertThat(m.messageAttributes().get("version").stringValue()).isEqualTo("1");
        assertThat(m.messageAttributes().get("correlationId").stringValue())
            .isEqualTo(envs.get(i).correlationId());
        assertThat(m.messageAttributes().containsKey("traceparent")).isEqualTo(i == 0);
      }
    }
  }

  @Test
  void p13ConcurrentRelaysPublishEachRowOnce() throws Exception {
    DataSource ds = Containers.postgres();
    for (int i = 0; i < 20; i++) {
      enqueue(ds, TOPIC_ARN, newEnvelope(), true);
    }
    Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    OutboxPublisher counting =
        (topic, env, traceparent) ->
            calls.computeIfAbsent(env.messageId(), k -> new AtomicInteger()).incrementAndGet();
    Runnable drain =
        () -> {
          OutboxRelay r = relay(ds, counting, 3);
          try {
            while (Containers.count(ds, "SELECT count(*) FROM outbox WHERE published_at IS NULL")
                > 0) {
              r.runOnce();
            }
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        };
    CompletableFuture.allOf(CompletableFuture.runAsync(drain), CompletableFuture.runAsync(drain))
        .get();
    assertThat(calls).hasSize(20);
    assertThat(calls.values()).allSatisfy(n -> assertThat(n.get()).isEqualTo(1));
  }

  // ---------- consumer ----------

  /**
   * Inserts one effects row per handled message; optionally fails the first call or ends with an
   * exception.
   */
  private static final class EffectHandler implements MessageHandler {
    private final AtomicInteger calls = new AtomicInteger();
    private final boolean failFirst;
    private final RuntimeException result;

    EffectHandler(boolean failFirst, RuntimeException result) {
      this.failFirst = failFirst;
      this.result = result;
    }

    @Override
    public Set<Integer> versions() {
      return Set.of(1);
    }

    @Override
    public void handle(Envelope envelope, Connection connection) throws Exception {
      if (calls.incrementAndGet() == 1 && failFirst) {
        throw new java.sql.SQLTransientConnectionException("database temporarily unavailable");
      }
      try (PreparedStatement ps =
          connection.prepareStatement("INSERT INTO effects (message_id) VALUES (?)")) {
        ps.setObject(1, UUID.fromString(envelope.messageId()));
        ps.executeUpdate();
      }
      if (result != null) {
        throw result;
      }
    }
  }

  private record Fixture(
      DataSource db, SqsClient sqs, String queue, String dlq, SqsConsumer consumer)
      implements AutoCloseable {

    static Fixture create(MessageHandler handler) throws Exception {
      DataSource db = Containers.postgres();
      SqsClient sqs = Containers.sqs();
      String[] q = Containers.queue(sqs, "consumer", 3);
      SqsConsumer consumer =
          new SqsConsumer(
              sqs,
              db,
              new ContractValidator(PlatformCoreTest.CONTRACTS),
              Map.of("UserCreationFailed", handler),
              new SqsConsumerProperties(q[0], "test-consumer", 4, 1, Containers.CLIENT_TIMEOUT));
      return new Fixture(db, sqs, q[0], q[1], consumer);
    }

    void send(String body) {
      sqs.sendMessage(b -> b.queueUrl(queue).messageBody(body));
    }

    List<ConsumeOutcome> receiveAndProcess() {
      List<ConsumeOutcome> outcomes = new ArrayList<>();
      for (Message m :
          sqs.receiveMessage(b ->
                  b.queueUrl(queue)
                      .maxNumberOfMessages(10)
                      .waitTimeSeconds(1)
                      .messageAttributeNames("All")
                      .messageSystemAttributeNames(
                          MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
              .messages()) {
        outcomes.add(consumer.process(m));
      }
      return outcomes;
    }

    @Override
    public void close() {
      sqs.close();
    }
  }

  private static String example(String name, String messageId) throws Exception {
    ObjectNode node =
        (ObjectNode)
            JSON.readTree(
                Files.readString(PlatformCoreTest.CONTRACTS.resolve("examples/" + name + ".json")));
    node.put("messageId", messageId);
    return JSON.writeValueAsString(node);
  }

  private static long count(List<ConsumeOutcome> outcomes, ConsumeOutcome want) {
    return outcomes.stream().filter(o -> o == want).count();
  }

  @Test
  void p09DuplicateFiveTimesHasOneEffect() throws Exception {
    try (Fixture f = Fixture.create(new EffectHandler(false, null))) {
      String body = example("UserCreationFailed.v1", MSG_ID);
      for (int i = 0; i < 5; i++) {
        f.send(body);
      }
      List<ConsumeOutcome> outcomes = new ArrayList<>();
      for (int i = 0; i < 10 && outcomes.size() < 5; i++) {
        outcomes.addAll(f.receiveAndProcess());
      }
      assertThat(count(outcomes, ConsumeOutcome.SUCCEEDED)).isEqualTo(1);
      assertThat(count(outcomes, ConsumeOutcome.DUPLICATE)).isEqualTo(4);
      assertThat(Containers.count(f.db(), "SELECT count(*) FROM effects")).isEqualTo(1);
      assertThat(Containers.count(f.db(), "SELECT count(*) FROM processed_messages")).isEqualTo(1);
      assertThat(Containers.queueDepth(f.sqs(), f.queue())).isZero();
    }
  }

  @Test
  void p10CrashBeforeCommitIsRedelivered() throws Exception {
    try (Fixture f = Fixture.create(new EffectHandler(true, null))) {
      f.send(example("UserCreationFailed.v1", MSG_ID));
      List<ConsumeOutcome> outcomes = new ArrayList<>();
      Instant deadline = Instant.now().plusSeconds(20);
      while (!outcomes.contains(ConsumeOutcome.SUCCEEDED) && Instant.now().isBefore(deadline)) {
        outcomes.addAll(f.receiveAndProcess());
      }
      assertThat(outcomes).first().isEqualTo(ConsumeOutcome.TRANSIENT);
      assertThat(outcomes).last().isEqualTo(ConsumeOutcome.SUCCEEDED);
      assertThat(Containers.count(f.db(), "SELECT count(*) FROM effects")).isEqualTo(1);
    }
  }

  @Test
  void p11PoisonGoesToDlq() throws Exception {
    try (Fixture f = Fixture.create(new EffectHandler(false, null))) {
      f.send(
          example("UserCreationFailed.v1", MSG_ID)
              .replace("0193a6f0-1111-7000-8000-000000000001", "nope"));
      for (int i = 0; i < 6 && Containers.queueDepth(f.sqs(), f.dlq()) == 0; i++) {
        assertThat(f.receiveAndProcess()).allMatch(o -> o == ConsumeOutcome.POISON);
      }
      assertThat(Containers.queueDepth(f.sqs(), f.dlq())).isEqualTo(1);
      assertThat(Containers.count(f.db(), "SELECT count(*) FROM effects")).isZero();

      // A valid message whose type has no handler is poison too, and is not acknowledged.
      f.send(example("UserRegistered.v1", "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a56"));
      assertThat(f.receiveAndProcess()).containsExactly(ConsumeOutcome.POISON);
      assertThat(Containers.queueDepth(f.sqs(), f.queue())).isEqualTo(1);
    }
  }

  @Test
  void p14StaleEventIsRecordedAndAcknowledged() throws Exception {
    try (Fixture f =
        Fixture.create(new EffectHandler(false, new StaleEventException("already final")))) {
      f.send(example("UserCreationFailed.v1", MSG_ID));
      assertThat(f.receiveAndProcess()).containsExactly(ConsumeOutcome.STALE);
      assertThat(Containers.count(f.db(), "SELECT count(*) FROM processed_messages")).isEqualTo(1);
      assertThat(Containers.queueDepth(f.sqs(), f.queue())).isZero();
    }
  }

  @Test
  void p15RawAndSnsFormatsAreBothConsumed() throws Exception {
    try (Fixture f = Fixture.create(new EffectHandler(false, null));
        SnsClient sns = Containers.sns()) {
      String topic = Containers.topic(sns, f.sqs(), "formats", f.queue());
      new SnsOutboxPublisher(sns)
          .publish(
              topic,
              EnvelopeCodec.unwrap(example("UserCreationFailed.v1", MSG_ID)),
              Optional.empty());
      String quoted =
          JSON.writeValueAsString(
              example("UserCreationFailed.v1", "0193a6f2-6c1e-7b9a-9d1f-3f2b1c0e4a57"));
      f.send(
          "{\"Type\":\"Notification\",\"MessageId\":\"n-1\",\"TopicArn\":\""
              + topic
              + "\",\"Message\":"
              + quoted
              + "}");

      List<ConsumeOutcome> outcomes = new ArrayList<>();
      for (int i = 0; i < 10 && outcomes.size() < 2; i++) {
        outcomes.addAll(f.receiveAndProcess());
      }
      assertThat(count(outcomes, ConsumeOutcome.SUCCEEDED)).isEqualTo(2);
    }
  }

  /**
   * SQS_WAIT_TIME_S (10) is longer than HTTP_CLIENT_TIMEOUT_MS (3000): the receive must override
   * the client-wide timeout or every long poll fails.
   */
  @Test
  void runLongPollLongerThanClientTimeout() throws Exception {
    DataSource db = Containers.postgres();
    try (SqsClient sqs = Containers.sqs()) {
      String queue = Containers.queue(sqs, "long-poll", 3)[0];
      int waitSeconds = (int) Containers.CLIENT_TIMEOUT.toSeconds() + 2;
      SqsConsumer consumer =
          new SqsConsumer(
              sqs,
              db,
              new ContractValidator(PlatformCoreTest.CONTRACTS),
              Map.of("UserCreationFailed", new EffectHandler(false, null)),
              new SqsConsumerProperties(
                  queue, "long-poll", 4, waitSeconds, Containers.CLIENT_TIMEOUT));
      AtomicBoolean stop = new AtomicBoolean();
      CompletableFuture<Void> run = CompletableFuture.runAsync(() -> consumer.run(stop::get));
      Thread.sleep(Containers.CLIENT_TIMEOUT.plusSeconds(1).toMillis());
      String body = example("UserCreationFailed.v1", MSG_ID);
      sqs.sendMessage(b -> b.queueUrl(queue).messageBody(body));
      Instant deadline = Instant.now().plusSeconds(15);
      while (Containers.count(db, "SELECT count(*) FROM effects") == 0) {
        assertThat(Instant.now()).as("message consumed by run").isBefore(deadline);
        Thread.sleep(200);
      }
      assertThat(consumer.receiveFailures())
          .as("no receive was cut by the client timeout")
          .isZero();
      stop.set(true);
      run.get();
    }
  }
}
