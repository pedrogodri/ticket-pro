package br.furb.ticketpro.auth.platform.messaging;

import br.furb.ticketpro.auth.platform.envelope.ContractValidator;
import br.furb.ticketpro.auth.platform.envelope.Envelope;
import br.furb.ticketpro.auth.platform.envelope.EnvelopeCodec;
import br.furb.ticketpro.auth.platform.envelope.PoisonMessageException;
import br.furb.ticketpro.auth.platform.envelope.StaleEventException;
import br.furb.ticketpro.auth.platform.envelope.UnsupportedVersionException;
import br.furb.ticketpro.auth.platform.idempotency.ProcessedMessages;
import br.furb.ticketpro.auth.platform.retry.Backoff;
import br.furb.ticketpro.auth.platform.tracing.Spans;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives from one SQS queue and applies handlers with ack-after-commit (ADR-0006, RFC 0003 §5.4).
 * The data source must use the worker role (DATABASE_WORKER_URL).
 */
public final class SqsConsumer {

  private static final Logger log = LoggerFactory.getLogger(SqsConsumer.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final SqsClient sqs;
  private final DataSource dataSource;
  private final ContractValidator validator;
  private final Map<String, MessageHandler> handlers;
  private final SqsConsumerProperties props;
  private final AtomicBoolean brokerUp = new AtomicBoolean(true);
  private final AtomicLong receiveFailures = new AtomicLong();

  public SqsConsumer(
      SqsClient sqs,
      DataSource dataSource,
      ContractValidator validator,
      Map<String, MessageHandler> handlers,
      SqsConsumerProperties props) {
    this.sqs = sqs;
    this.dataSource = dataSource;
    this.validator = validator;
    this.handlers = Map.copyOf(handlers);
    this.props = props;
  }

  /** Whether the last receive succeeded. */
  public boolean brokerUp() {
    return brokerUp.get();
  }

  /** Receive calls that failed since start (broker down, timeouts). */
  public long receiveFailures() {
    return receiveFailures.get();
  }

  /**
   * Receives and processes until {@code stop} is true; receive errors back off. In-flight messages
   * finish.
   */
  public void run(BooleanSupplier stop) {
    // The client-wide timeout (HTTP_CLIENT_TIMEOUT_MS) is shorter than the long poll
    // (SQS_WAIT_TIME_S).
    Duration longPoll = Duration.ofSeconds(props.waitTimeSeconds()).plus(props.requestTimeout());
    ReceiveMessageRequest request =
        ReceiveMessageRequest.builder()
            .queueUrl(props.queueUrl())
            .maxNumberOfMessages(Math.min(Math.max(props.concurrency(), 1), 10))
            .waitTimeSeconds(props.waitTimeSeconds())
            .messageAttributeNames("All")
            .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
            .overrideConfiguration(o -> o.apiCallTimeout(longPoll).apiCallAttemptTimeout(longPoll))
            .build();
    int failures = 0;
    try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
      while (!stop.getAsBoolean()) {
        List<Message> messages;
        try {
          messages = sqs.receiveMessage(request).messages();
        } catch (SdkException e) {
          receiveFailures.incrementAndGet();
          if (brokerUp.getAndSet(false)) {
            log.atWarn().addKeyValue("error", e.getMessage()).log("broker_down");
          }
          sleep(
              Backoff.delay(
                  failures++,
                  Backoff.DEFAULT_BASE,
                  Backoff.DEFAULT_CAP,
                  () -> ThreadLocalRandom.current().nextDouble()));
          continue;
        }
        if (!brokerUp.getAndSet(true)) {
          log.info("broker_up");
        }
        failures = 0;
        CompletableFuture.allOf(
                messages.stream()
                    .map(m -> CompletableFuture.runAsync(() -> process(m), workers))
                    .toArray(CompletableFuture[]::new))
            .join();
      }
    }
  }

  /** Applies one message following RFC 0003 §5.4 and acknowledges only after commit. */
  public ConsumeOutcome process(Message message) {
    Envelope envelope;
    try {
      validator.validate(EnvelopeCodec.rawEnvelope(message.body()));
      envelope = EnvelopeCodec.unwrap(message.body());
    } catch (PoisonMessageException e) {
      return poison(message, e);
    }
    MDC.put("correlationId", envelope.correlationId());
    MDC.put("messageId", envelope.messageId());
    MDC.put("type", envelope.type());
    MDC.put("version", Integer.toString(envelope.version()));
    // ADR-0012: a consumer span in the producer's trace, linked to the producer span.
    Span span =
        Spans.consumer(
            traceparentOf(message),
            envelope.type(),
            props.queueUrl(),
            envelope.messageId(),
            props.name());
    SpanContext current = span.getSpanContext();
    if (current.isValid()) {
      MDC.put("traceId", current.getTraceId());
      MDC.put("spanId", current.getSpanId());
    }
    ConsumeOutcome outcome = null;
    try (Scope ignored = span.makeCurrent()) {
      MessageHandler handler = handlers.get(envelope.type());
      if (handler == null || !handler.versions().contains(envelope.version())) {
        outcome =
            poison(
                message,
                new UnsupportedVersionException(
                    "no handler for " + envelope.type() + " v" + envelope.version()));
      } else {
        outcome = apply(message, envelope, handler);
      }
      return outcome;
    } finally {
      if (outcome == null
          || outcome == ConsumeOutcome.POISON
          || outcome == ConsumeOutcome.TRANSIENT) {
        span.setStatus(StatusCode.ERROR, outcome == null ? "failed" : outcome.name());
      }
      span.end();
      MDC.clear();
    }
  }

  private ConsumeOutcome apply(Message message, Envelope envelope, MessageHandler handler) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        if (!ProcessedMessages.record(c, props.name(), UUID.fromString(envelope.messageId()))) {
          c.rollback();
          ack(message);
          log.atInfo().addKeyValue("duplicate", true).log("message_consumed");
          return ConsumeOutcome.DUPLICATE;
        }
        boolean stale = false;
        try {
          handler.handle(envelope, c);
        } catch (StaleEventException e) {
          stale = true;
        }
        c.commit();
        ack(message);
        if (stale) {
          log.info("stale_event");
          return ConsumeOutcome.STALE;
        }
        log.info("message_consumed");
        return ConsumeOutcome.SUCCEEDED;
      } catch (PoisonMessageException e) {
        c.rollback();
        return poison(message, e);
      } catch (Exception e) {
        safeRollback(c);
        return transientFailure(message, e);
      }
    } catch (SQLException e) {
      return transientFailure(message, e);
    }
  }

  private static void safeRollback(Connection c) {
    try {
      c.rollback();
    } catch (SQLException ignored) {
      // Connection already broken: the server discards the transaction.
    }
  }

  private ConsumeOutcome poison(Message message, PoisonMessageException cause) {
    log.atWarn()
        .addKeyValue("poison", true)
        .addKeyValue(
            "reason",
            cause instanceof UnsupportedVersionException
                ? "unsupported_version"
                : "invalid_message")
        .addKeyValue("error", cause.getMessage())
        .log("message_rejected");
    changeVisibility(message, 0); // reach maxReceiveCount fast → DLQ via redrive
    return ConsumeOutcome.POISON;
  }

  private ConsumeOutcome transientFailure(Message message, Exception cause) {
    String raw = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
    int receiveCount = raw == null ? 0 : Integer.parseInt(raw);
    Duration delay =
        Backoff.delay(
            receiveCount,
            Backoff.DEFAULT_BASE,
            Backoff.DEFAULT_CAP,
            () -> ThreadLocalRandom.current().nextDouble());
    int seconds = (int) Math.max(1, Math.ceil(delay.toMillis() / 1000.0));
    log.atWarn()
        .addKeyValue("error", cause.getMessage())
        .addKeyValue("receiveCount", receiveCount)
        .addKeyValue("visibilityS", seconds)
        .log("message_retry");
    changeVisibility(message, seconds);
    return ConsumeOutcome.TRANSIENT;
  }

  private void ack(Message message) {
    try {
      sqs.deleteMessage(b -> b.queueUrl(props.queueUrl()).receiptHandle(message.receiptHandle()));
    } catch (SdkException e) {
      // Committed but not acked: the redelivery is detected as a duplicate.
      log.atWarn().addKeyValue("error", e.getMessage()).log("ack_failed");
    }
  }

  private void changeVisibility(Message message, int seconds) {
    try {
      sqs.changeMessageVisibility(b ->
          b.queueUrl(props.queueUrl())
              .receiptHandle(message.receiptHandle())
              .visibilityTimeout(seconds));
    } catch (SdkException e) {
      log.atWarn().addKeyValue("error", e.getMessage()).log("change_visibility_failed");
    }
  }

  // Raw delivery puts traceparent in the SQS attributes; an SNS notification keeps it inside the
  // body (spike S2).
  private static String traceparentOf(Message message) {
    var attr = message.messageAttributes().get("traceparent");
    if (attr != null) {
      return attr.stringValue();
    }
    try {
      JsonNode value =
          JSON.readTree(message.body()).path("MessageAttributes").path("traceparent").path("Value");
      return value.isString() ? value.asString() : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
